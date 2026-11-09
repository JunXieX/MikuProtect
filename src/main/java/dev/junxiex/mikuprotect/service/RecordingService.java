package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.api.event.MikuProtectPreLogEvent;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import dev.junxiex.mikuprotect.model.ContainerDraft;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.storage.StorageException;
import dev.junxiex.mikuprotect.util.ItemCodec;
import dev.junxiex.mikuprotect.util.TileEntityCodec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;

/**
 * Bounded in-memory queue plus a single writer thread. Listeners only enqueue snapshots, they never
 * touch JDBC.
 */
public final class RecordingService {

    private final MikuProtectPlugin plugin;
    private volatile MikuConfig config;
    private final Storage storage;
    private final BlockingQueue<QueuedRecord> queue;
    private final ContainerAggregator containerAggregator;
    private final ContainerDraftAggregator containerDraftAggregator;
    private final WalService wal;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    /** 写线程与 /mp flush 共用同一把写锁，避免两个事务同时插入维度行。 */
    private final Object writeLock = new Object();
    private volatile boolean running;
    private Thread worker;

    private interface QueuedRecord {
        ActivityRecord materialize();
    }

    private record Immediate(ActivityRecord record) implements QueuedRecord {
        @Override
        public ActivityRecord materialize() {
            return record;
        }
    }

    private record DeferredBlock(ActivityRecord skeleton, BlockState now, BlockState old)
            implements QueuedRecord {
        @Override
        public ActivityRecord materialize() {
            return skeleton.withMeta(TileEntityCodec.encode(now), TileEntityCodec.encode(old));
        }
    }

    private record DeferredSign(ActivityRecord skeleton, BlockState now, BlockState old)
            implements QueuedRecord {
        @Override
        public ActivityRecord materialize() {
            return skeleton.withMeta(TileEntityCodec.encode(now), TileEntityCodec.encode(old));
        }
    }

    public RecordingService(MikuProtectPlugin plugin, MikuConfig config, Storage storage) {
        this.plugin = plugin;
        this.config = config;
        this.storage = storage;
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity);
        this.containerAggregator = new ContainerAggregator(config.containerAggregationWindowMs);
        this.containerDraftAggregator = new ContainerDraftAggregator(config.containerAggregationWindowMs);
        this.wal = new WalService(plugin.getDataFolder().toPath().resolve("wal"), plugin.getLogger(),
                config.walEnabled);
    }

    /** Replaces the filter snapshot after /mp reload. Queue capacity and batch size stay unchanged. */
    public void updateConfig(MikuConfig next) {
        if (next != null) {
            this.config = next;
        }
    }

    public void start() {
        if (running) {
            return;
        }
        wal.replay(storage);
        running = true;
        worker = Thread.ofPlatform()
                .name("MikuProtect-Writer")
                .daemon(true)
                .unstarted(this::loop);
        worker.start();
    }

    public boolean record(ActivityRecord record) {
        if (record == null || record.action == null) {
            return false;
        }
        if ((Bukkit.isPrimaryThread() || dev.junxiex.mikuprotect.util.Scheduler.folia())
                && hasPreLogListeners()) {
            MikuProtectPreLogEvent event = new MikuProtectPreLogEvent(record);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
        }
        if (!acceptBasic(record)) {
            return false;
        }
        if (config.aggregateContainers && record.action.category() == dev.junxiex.mikuprotect.model.ActionType.Category.CONTAINER
                && !record.containers.isEmpty() && containerAggregator.offer(record)) {
            return true;
        }
        return enqueue(record);
    }

    /**
     * Captures tile snapshots on the region thread and defers tile serialization to the writer.
     * If an API listener is present, materialize immediately so PreLog sees the complete payload.
     */
    public boolean recordDeferredBlock(ActivityRecord skeleton, BlockState now, BlockState old) {
        if (skeleton == null || skeleton.action == null) {
            return false;
        }
        boolean primary = Bukkit.isPrimaryThread() || dev.junxiex.mikuprotect.util.Scheduler.folia();
        if (primary && hasPreLogListeners()) {
            try {
                return record(skeleton.withMeta(TileEntityCodec.encode(now), TileEntityCodec.encode(old)));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to serialize block metadata for PreLog", exception);
                return record(skeleton);
            }
        }
        if (!acceptBasic(skeleton)) {
            return false;
        }
        return enqueue(new DeferredBlock(skeleton, now, old));
    }

    /**
     * Queues container snapshots without encoding ItemStacks on the region thread. Aggregation
     * still runs before the queue, so hopper bursts keep their compact row count.
     */
    public boolean recordDeferredContainer(Location location, ActionType action, Player player,
            List<ContainerDraft> drafts) {
        if (location == null || location.getWorld() == null || action == null || drafts == null
                || drafts.isEmpty()) {
            return false;
        }
        if (!config.aggregateContainers) {
            return record(materializeContainer(location, action, player, drafts));
        }
        ActivityRecord skeleton = ActivityRecord.container(location, action, player, List.of());
        boolean primary = Bukkit.isPrimaryThread() || dev.junxiex.mikuprotect.util.Scheduler.folia();
        if (primary && hasPreLogListeners()) {
            return record(materializeContainer(location, action, player, drafts));
        }
        if (!acceptBasic(skeleton)) {
            return false;
        }
        return containerDraftAggregator.offer(location, action, player, drafts);
    }

    private static ActivityRecord materializeContainer(Location location, ActionType action, Player player,
            List<ContainerDraft> drafts) {
        List<ContainerChange> changes = new ArrayList<>(drafts.size());
        for (ContainerDraft draft : drafts) {
            changes.add(new ContainerChange(draft.slot(), ItemCodec.encode(draft.before()),
                    ItemCodec.encode(draft.after()), draft.beforeAmount(), draft.afterAmount()));
        }
        return ActivityRecord.container(location, action, player, changes);
    }

    public boolean recordDeferredSign(ActivityRecord skeleton, BlockState now, BlockState old) {
        if (skeleton == null || skeleton.action == null) {
            return false;
        }
        boolean primary = Bukkit.isPrimaryThread() || dev.junxiex.mikuprotect.util.Scheduler.folia();
        if (primary && hasPreLogListeners()) {
            try {
                return record(skeleton.withMeta(TileEntityCodec.encode(now), TileEntityCodec.encode(old)));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to serialize sign metadata for PreLog", exception);
                return record(skeleton);
            }
        }
        if (!acceptBasic(skeleton)) {
            return false;
        }
        return enqueue(new DeferredSign(skeleton, now, old));
    }

    private boolean acceptBasic(ActivityRecord record) {
        if (config.disabledActions.contains(record.action)) {
            return false;
        }
        if (config.ignoreCreative && record.userName != null) {
            Player online = Bukkit.getPlayerExact(record.userName);
            if (online != null && online.getGameMode() == GameMode.CREATIVE) {
                return false;
            }
        }
        return config.worlds.isEmpty() || record.worldName == null || config.worlds.contains(record.worldName);
    }

    private static boolean hasPreLogListeners() {
        return MikuProtectPreLogEvent.getHandlerList().getRegisteredListeners().length > 0;
    }

    private boolean enqueue(ActivityRecord record) {
        return enqueue(new Immediate(record));
    }

    private boolean enqueue(QueuedRecord queued) {
        if (!queue.offer(queued)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                plugin.getLogger().warning("Recording queue is full, dropped " + count + " records");
            }
            return false;
        }
        return true;
    }

    private void loop() {
        List<QueuedRecord> batch = new ArrayList<>(config.batchSize);
        while (running || !queue.isEmpty()) {
            try {
                containerAggregator.flush(this::enqueue, false);
                containerDraftAggregator.flush(this::enqueue, false);
                QueuedRecord first = queue.poll(config.flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, Math.max(0, config.batchSize - batch.size()));
                writeBatch(batch);
                batch.clear();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Writer loop failure", exception);
            }
        }
    }

    private void writeBatch(List<QueuedRecord> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<ActivityRecord> records = materialize(batch);
        if (records.isEmpty()) {
            return;
        }
        int attempts = 0;
        while (true) {
            try {
                synchronized (writeLock) {
                    storage.write(records);
                }
                written.addAndGet(records.size());
                return;
            } catch (StorageException exception) {
                attempts++;
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to write " + records.size() + " records (attempt " + attempts + ")", exception);
                if (attempts >= 3) {
                    wal.append(records);
                    return;
                }
                try {
                    Thread.sleep(Math.min(5000L, 250L * attempts));
                } catch (InterruptedException interrupted) {
                    // Clear the interrupt flag before file I/O; FileChannel aborts on interrupt.
                    Thread.interrupted();
                    wal.append(records);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private List<ActivityRecord> materialize(List<QueuedRecord> batch) {
        List<ActivityRecord> records = new ArrayList<>(batch.size());
        for (QueuedRecord queued : batch) {
            try {
                records.add(queued.materialize());
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to serialize queued record", exception);
            }
        }
        return records;
    }

    /**
     * Drains the queue synchronously. Used on disable and by /mp flush.
     */
    public void flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        containerAggregator.flush(this::enqueue, true);
        containerDraftAggregator.flush(this::enqueue, true);
        List<QueuedRecord> batch = new ArrayList<>(config.batchSize);
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            batch.clear();
            queue.drainTo(batch, config.batchSize);
            if (batch.isEmpty()) {
                break;
            }
            List<ActivityRecord> records = materialize(batch);
            if (records.isEmpty()) {
                continue;
            }
            try {
                synchronized (writeLock) {
                    storage.write(records);
                }
                written.addAndGet(records.size());
            } catch (StorageException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to flush " + records.size() + " records", exception);
                wal.append(records);
                break;
            }
        }
        storage.flush();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        flush(Duration.ofSeconds(10));
        if (!queue.isEmpty()) {
            List<QueuedRecord> remaining = new ArrayList<>(queue.size());
            queue.drainTo(remaining);
            List<ActivityRecord> records = materialize(remaining);
            if (!records.isEmpty()) {
                wal.append(records);
            }
        }
    }

    public int size() {
        return queue.size();
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    public int capacity() {
        return config.queueCapacity;
    }
}
