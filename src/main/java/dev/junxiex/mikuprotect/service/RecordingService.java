package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.api.event.MikuProtectPreLogEvent;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.storage.StorageException;
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
import org.bukkit.entity.Player;

/**
 * Bounded in-memory queue plus a single writer thread. Listeners only enqueue snapshots, they never
 * touch JDBC.
 */
public final class RecordingService {

    private final MikuProtectPlugin plugin;
    private volatile MikuConfig config;
    private final Storage storage;
    private final BlockingQueue<ActivityRecord> queue;
    private final ContainerAggregator containerAggregator;
    private final WalService wal;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    /** 写线程与 /mp flush 共用同一把写锁，避免两个事务同时插入维度行。 */
    private final Object writeLock = new Object();
    private volatile boolean running;
    private Thread worker;

    public RecordingService(MikuProtectPlugin plugin, MikuConfig config, Storage storage) {
        this.plugin = plugin;
        this.config = config;
        this.storage = storage;
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity);
        this.containerAggregator = new ContainerAggregator(config.containerAggregationWindowMs);
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
        if (Bukkit.isPrimaryThread() || dev.junxiex.mikuprotect.util.Scheduler.folia()) {
            MikuProtectPreLogEvent event = new MikuProtectPreLogEvent(record);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
        }
        if (config.disabledActions.contains(record.action)) {
            return false;
        }
        if (config.ignoreCreative && record.userName != null) {
            Player online = Bukkit.getPlayerExact(record.userName);
            if (online != null && online.getGameMode() == GameMode.CREATIVE) {
                return false;
            }
        }
        if (!config.worlds.isEmpty() && record.worldName != null && !config.worlds.contains(record.worldName)) {
            return false;
        }
        if (config.aggregateContainers && record.action.category() == dev.junxiex.mikuprotect.model.ActionType.Category.CONTAINER
                && !record.containers.isEmpty() && containerAggregator.offer(record)) {
            return true;
        }
        return enqueue(record);
    }

    private boolean enqueue(ActivityRecord record) {
        if (!queue.offer(record)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                plugin.getLogger().warning("Recording queue is full, dropped " + count + " records");
            }
            return false;
        }
        return true;
    }

    private void loop() {
        List<ActivityRecord> batch = new ArrayList<>(config.batchSize);
        while (running || !queue.isEmpty()) {
            try {
                containerAggregator.flush(this::enqueue, false);
                ActivityRecord first = queue.poll(config.flushIntervalMs, TimeUnit.MILLISECONDS);
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

    private void writeBatch(List<ActivityRecord> batch) {
        if (batch.isEmpty()) {
            return;
        }
        int attempts = 0;
        while (true) {
            try {
                synchronized (writeLock) {
                    storage.write(batch);
                }
                written.addAndGet(batch.size());
                return;
            } catch (StorageException exception) {
                attempts++;
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to write " + batch.size() + " records (attempt " + attempts + ")", exception);
                if (attempts >= 3) {
                    wal.append(batch);
                    return;
                }
                try {
                    Thread.sleep(Math.min(5000L, 250L * attempts));
                } catch (InterruptedException interrupted) {
                    // Clear the interrupt flag before file I/O; FileChannel aborts on interrupt.
                    Thread.interrupted();
                    wal.append(batch);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Drains the queue synchronously. Used on disable and by /mp flush.
     */
    public void flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        containerAggregator.flush(this::enqueue, true);
        List<ActivityRecord> batch = new ArrayList<>(config.batchSize);
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            batch.clear();
            queue.drainTo(batch, config.batchSize);
            if (batch.isEmpty()) {
                break;
            }
            try {
                synchronized (writeLock) {
                    storage.write(batch);
                }
                written.addAndGet(batch.size());
            } catch (StorageException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to flush " + batch.size() + " records", exception);
                wal.append(batch);
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
            List<ActivityRecord> remaining = new ArrayList<>(queue.size());
            queue.drainTo(remaining);
            if (!remaining.isEmpty()) {
                wal.append(remaining);
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
