package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.AppliedChange;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.RollbackRequest;
import dev.junxiex.mikuprotect.model.RollbackResult;
import dev.junxiex.mikuprotect.model.StoredActivity;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.util.BlockCodec;
import dev.junxiex.mikuprotect.util.ItemCodec;
import dev.junxiex.mikuprotect.util.Scheduler;
import dev.junxiex.mikuprotect.util.TileEntityCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Applies rollback/restore/preview changes in chunk sized, tick budgeted batches.
 *
 * <p>The engine never trusts the database alone: every target state is applied with physics
 * disabled by default, then the tile-entity payload is restored. That keeps redstone component
 * block states (powered, extended, delay, locked, ...) exactly as logged instead of letting a
 * cascade overwrite them.</p>
 */
public final class RollbackService {

    private final MikuProtectPlugin plugin;
    private final Storage storage;
    private static final long TASK_TIMEOUT_MS = 5L * 60L * 1000L;

    private final Map<UUID, PreviewSession> previews = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> running = new ConcurrentHashMap<>();

    public RollbackService(MikuProtectPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public CompletableFuture<RollbackResult> apply(RollbackRequest request) {
        CompletableFuture<RollbackResult> future = new CompletableFuture<>();
        UUID actor = actorId(request.sender);
        if (actor != null && running.putIfAbsent(actor, Boolean.TRUE) != null) {
            future.completeExceptionally(new IllegalStateException("已有回滚/还原任务在运行"));
            return future;
        }
        watch(actor, future);
        try {
            Scheduler.async(() -> {
            try {
                LookupQuery query = request.query;
                query.page = 1;
                query.limit = Math.min(Math.max(1, query.limit), Math.max(1, request.maxRows));
                query.ascending = request.mode == RollbackRequest.Mode.RESTORE;
                List<StoredActivity> rows = storage.query(query, Math.max(1, request.maxRows));
                List<PlannedChange> planned = new ArrayList<>(rows.size());
                RollbackResult plannedResult = new RollbackResult();
                plan(rows, request.mode, planned, plannedResult);
                if (request.mode == RollbackRequest.Mode.PREVIEW) {
                    Scheduler.global(() -> stagePreview(actor, planned, request, plannedResult, future));
                    return;
                }
                boolean rollbackMode = request.mode == RollbackRequest.Mode.ROLLBACK;
                Scheduler.global(() -> executePlanned(actor, planned, request, plannedResult, future,
                        true, rollbackMode, true));
            } catch (Exception exception) {
                plugin.getLogger().warning("Rollback planning failed: " + exception.getMessage());
                if (actor != null) {
                    running.remove(actor);
                }
                future.completeExceptionally(exception);
            }
            });
        } catch (RuntimeException exception) {
            if (actor != null) {
                running.remove(actor);
            }
            future.completeExceptionally(exception);
        }
        return future;
    }

    public CompletableFuture<RollbackResult> applyPreview(CommandSender sender) {
        CompletableFuture<RollbackResult> future = new CompletableFuture<>();
        UUID actor = actorId(sender);
        if (actor != null && running.putIfAbsent(actor, Boolean.TRUE) != null) {
            future.completeExceptionally(new IllegalStateException("已有回滚/还原任务在运行"));
            return future;
        }
        watch(actor, future);
        PreviewSession session = actor == null ? null : previews.remove(actor);
        if (session == null) {
            if (actor != null) {
                running.remove(actor);
            }
            future.completeExceptionally(new IllegalStateException("没有待确认的预览"));
            return future;
        }
        RollbackRequest request = new RollbackRequest(RollbackRequest.Mode.ROLLBACK, new LookupQuery(),
                sender, plugin.config().rollbackMaxRows, plugin.config().applyPhysics);
        try {
            Scheduler.global(() -> executePlanned(actor, session.planned, request, new RollbackResult(), future,
                    true, true, true));
        } catch (RuntimeException exception) {
            if (actor != null) {
                running.remove(actor);
                previews.put(actor, session);
            }
            future.completeExceptionally(exception);
        }
        return future;
    }

    public boolean cancelPreview(CommandSender sender) {
        UUID actor = actorId(sender);
        PreviewSession session = actor == null ? null : previews.remove(actor);
        if (session == null || !(sender instanceof Player player)) {
            return false;
        }
        for (PlannedChange change : session.planned) {
            if (change.targetBlock() == null) {
                continue;
            }
            World world = Bukkit.getWorld(change.worldUuid());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(change.x(), change.y(), change.z());
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
        return true;
    }

    public CompletableFuture<RollbackResult> applyUndo(CommandSender sender) {
        CompletableFuture<RollbackResult> future = new CompletableFuture<>();
        UUID actor = actorId(sender);
        if (actor != null && running.putIfAbsent(actor, Boolean.TRUE) != null) {
            future.completeExceptionally(new IllegalStateException("已有回滚/还原任务在运行"));
            return future;
        }
        watch(actor, future);
        List<AppliedChange> changes = plugin.undo().take(actor);
        if (changes == null || changes.isEmpty()) {
            if (actor != null) {
                running.remove(actor);
            }
            future.completeExceptionally(new IllegalStateException("没有可撤销的操作"));
            return future;
        }
        List<PlannedChange> planned = new ArrayList<>(changes.size());
        for (AppliedChange change : changes) {
            // 撤销“生成实体”时要移除它；撤销“移除实体”时重新生成
            boolean undoRemove = !change.removeEntity() && change.entityUuid() != null;
            planned.add(new PlannedChange(change.activityId(), change.worldUuid(), change.x(), change.y(),
                    change.z(), change.beforeBlockData(), change.beforeMeta(), change.beforeItem(), change.slot(),
                    change.beforeText(), change.entityType(), change.entityUuid(), undoRemove));
        }
        RollbackRequest request = new RollbackRequest(RollbackRequest.Mode.ROLLBACK, new LookupQuery(),
                sender, plugin.config().rollbackMaxRows, plugin.config().applyPhysics);
        try {
            Scheduler.global(() -> executePlanned(actor, planned, request, new RollbackResult(), future,
                    false, false, false));
        } catch (RuntimeException exception) {
            if (actor != null) {
                running.remove(actor);
                plugin.undo().remember(actor, changes);
            }
            future.completeExceptionally(exception);
        }
        return future;
    }

    private void plan(List<StoredActivity> rows, RollbackRequest.Mode mode, List<PlannedChange> planned,
            RollbackResult result) {
        boolean rollback = mode != RollbackRequest.Mode.RESTORE;
        for (StoredActivity row : rows) {
            if (!row.action().reversible()) {
                result.skipped++;
                continue;
            }
            switch (row.action().category()) {
                case BLOCK, WORLD -> {
                    String target = rollback ? row.oldBlockData() : row.blockData();
                    byte[] meta = rollback ? row.oldMeta() : row.meta();
                    if (target == null || target.isBlank()) {
                        target = "minecraft:air";
                    }
                    planned.add(new PlannedChange(row.id(), row.worldUuid(), row.x(), row.y(), row.z(),
                            target, meta, null, -1, null, null, null, false));
                }
                case CONTAINER -> {
                    for (StoredActivity.StoredContainer container : row.containers()) {
                        byte[] target = rollback ? container.beforeData() : container.afterData();
                        planned.add(new PlannedChange(row.id(), row.worldUuid(), row.x(), row.y(), row.z(),
                                null, null, target, container.slot(), null, null, null, false));
                    }
                }
                case SIGN -> {
                    String target = rollback ? row.oldBlockData() : row.blockData();
                    byte[] meta = rollback ? row.oldMeta() : row.meta();
                    planned.add(new PlannedChange(row.id(), row.worldUuid(), row.x(), row.y(), row.z(),
                            null, meta, null, -1, target == null ? "" : target, null, null, false));
                }
                case ENTITY -> {
                    boolean remove;
                    if (row.action() == ActionType.ENTITY_SPAWN) {
                        remove = rollback;
                    } else if (row.action() == ActionType.ENTITY_KILL) {
                        remove = !rollback;
                    } else {
                        result.skipped++;
                        continue;
                    }
                    if (!remove && row.meta() == null) {
                        // Without a payload, spawning only the type loses villager trades/frame contents.
                        result.skipped++;
                        continue;
                    }
                    planned.add(new PlannedChange(row.id(), row.worldUuid(), row.x(), row.y(), row.z(),
                            null, row.meta(), null, -1, null, row.entityType(), row.entityUuid(), remove));
                }
                default -> result.skipped++;
            }
        }
        result.completed = true;
    }

    private void stagePreview(UUID actor, List<PlannedChange> planned, RollbackRequest request,
            RollbackResult result, CompletableFuture<RollbackResult> future) {
        if (actor != null) {
            previews.put(actor, new PreviewSession(List.copyOf(planned)));
        }
        if (request.sender instanceof Player player) {
            try {
                for (PlannedChange change : planned) {
                    if (change.targetBlock() == null) {
                        continue;
                    }
                    World world = Bukkit.getWorld(change.worldUuid());
                    if (world == null) {
                        continue;
                    }
                    org.bukkit.block.data.BlockData data = BlockCodec.decode(change.targetBlock());
                    if (data != null) {
                        player.sendBlockChange(new Location(world, change.x(), change.y(), change.z()), data);
                    }
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Preview packet update failed: " + exception.getMessage());
            }
        }
        result.applied = planned.size();
        result.completed = true;
        if (actor != null) {
            running.remove(actor);
        }
        future.complete(result);
    }

    private void executePlanned(UUID actor, List<PlannedChange> planned, RollbackRequest request,
            RollbackResult result, CompletableFuture<RollbackResult> future, boolean markState,
            boolean stateValue, boolean rememberUndo) {
        if (planned.isEmpty()) {
            result.completed = true;
            if (actor != null) {
                running.remove(actor);
            }
            future.complete(result);
            return;
        }
        Map<ChunkKey, ChunkWork> chunks = new HashMap<>();
        for (PlannedChange change : planned) {
            ChunkKey key = new ChunkKey(change.worldUuid(), change.x() >> 4, change.z() >> 4);
            chunks.computeIfAbsent(key, ignored -> new ChunkWork(change.worldUuid(), change.x() >> 4,
                    change.z() >> 4)).changes.add(change);
        }
        List<ChunkWork> works = new ArrayList<>(chunks.values());
        AtomicInteger remaining = new AtomicInteger(works.size());
        for (ChunkWork work : works) {
            World world = Bukkit.getWorld(work.worldUuid);
            if (world == null) {
                work.result.failed += work.changes.size();
                work.result.completed = true;
                if (remaining.decrementAndGet() == 0) {
                    mergeAndFinish(works, result, actor, request, future, rememberUndo);
                }
                continue;
            }
            Location anchor = new Location(world, work.chunkX << 4, 64, work.chunkZ << 4);
            Scheduler.at(anchor, () -> runChunk(work, works, request, result, remaining, future,
                    markState, stateValue, rememberUndo, actor));
        }
    }

    private void watch(UUID actor, CompletableFuture<RollbackResult> future) {
        try {
            Scheduler.asyncDelayed(() -> {
                if (!future.isDone()) {
                    if (actor != null) {
                        running.remove(actor);
                    }
                    future.completeExceptionally(new IllegalStateException("回滚任务超时，目标区块可能未加载"));
                }
            }, TASK_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            // scheduler unavailable during shutdown; normal completion handles the future
        }
    }

    private void mergeAndFinish(List<ChunkWork> works, RollbackResult result, UUID actor,
            RollbackRequest request, CompletableFuture<RollbackResult> future, boolean rememberUndo) {
        for (ChunkWork work : works) {
            result.merge(work.result);
        }
        finish(actor, request, result, future, rememberUndo);
    }

    private void runChunk(ChunkWork work, List<ChunkWork> works, RollbackRequest request,
            RollbackResult result, AtomicInteger remaining, CompletableFuture<RollbackResult> future,
            boolean markState, boolean stateValue, boolean rememberUndo, UUID actor) {
        World world = Bukkit.getWorld(work.worldUuid);
        if (world == null) {
            work.result.failed += work.changes.size() - work.index;
            work.result.completed = true;
            if (remaining.decrementAndGet() == 0) {
                mergeAndFinish(works, result, actor, request, future, rememberUndo);
            }
            return;
        }
        long deadline = System.nanoTime() + budgetNanos();
        while (work.index < work.changes.size()) {
            if (System.nanoTime() >= deadline) {
                Scheduler.at(new Location(world, work.chunkX << 4, 64, work.chunkZ << 4),
                        () -> runChunk(work, works, request, result, remaining, future, markState, stateValue,
                                rememberUndo, actor));
                return;
            }
            PlannedChange change = work.changes.get(work.index++);
            try {
                AppliedChange applied = applyChange(world, change, request);
                if (applied != null) {
                    work.result.applied++;
                    work.result.changes.add(applied);
                    work.appliedIds.add(change.activityId());
                    if (plugin.config().redstoneRefresh && change.targetBlock() != null) {
                        org.bukkit.Material material = world.getBlockAt(change.x(), change.y(), change.z()).getType();
                        if (isRedstone(material)) {
                            work.redstone.add(new Location(world, change.x(), change.y(), change.z()));
                        }
                    }
                } else {
                    work.result.skipped++;
                }
            } catch (RuntimeException exception) {
                work.result.failed++;
                if (work.result.errors.size() < 20) {
                    work.result.errors.add(change.x() + "," + change.y() + "," + change.z() + ": "
                            + exception.getMessage());
                }
            }
        }
        refreshRedstone(work);
        if (markState && !work.appliedIds.isEmpty()) {
            List<Long> ids = List.copyOf(work.appliedIds);
            boolean value = stateValue;
            Scheduler.async(() -> {
                try {
                    storage.markRolledBack(ids, value);
                } catch (Exception exception) {
                    plugin.getLogger().warning("Failed to mark rolled back: " + exception.getMessage());
                }
            });
        }
        work.result.completed = true;
        if (remaining.decrementAndGet() == 0) {
            mergeAndFinish(works, result, actor, request, future, rememberUndo);
        }
    }

    /**
     * Opt-in: give redstone components one controlled block update after the whole chunk is
     * restored. Off by default because a machine caught mid-cycle may cascade.
     */
    private void refreshRedstone(ChunkWork work) {
        if (!plugin.config().redstoneRefresh || work.redstone.isEmpty()) {
            return;
        }
        for (Location location : work.redstone) {
            try {
                Block block = location.getBlock();
                block.setBlockData(block.getBlockData().clone(), true);
            } catch (RuntimeException ignored) {
                // a component may have been removed by an earlier update in the same pass
            }
        }
    }

    private static boolean isRedstone(org.bukkit.Material material) {
        String name = material.name();
        return name.contains("REDSTONE")
                || name.contains("REPEATER")
                || name.contains("COMPARATOR")
                || name.contains("OBSERVER")
                || name.contains("PISTON")
                || name.contains("BUTTON")
                || name.contains("PRESSURE_PLATE")
                || name.contains("RAIL")
                || name.contains("TRIPWIRE")
                || name.contains("DAYLIGHT_DETECTOR")
                || name.contains("SCULK_SENSOR")
                || name.contains("TARGET")
                || name.contains("LEVER")
                || name.contains("NOTE_BLOCK")
                || name.contains("LECTERN")
                || name.contains("JUKEBOX")
                || name.contains("CAMPFIRE");
    }

    private AppliedChange applyChange(World world, PlannedChange change, RollbackRequest request) {
        Location location = new Location(world, change.x(), change.y(), change.z());
        if (!Bukkit.isOwnedByCurrentRegion(location)) {
            throw new IllegalStateException("not owned by current region");
        }
        if (change.targetBlock() != null) {
            Block block = world.getBlockAt(location);
            BlockState beforeState = block.getState();
            String beforeData = block.getBlockData().getAsString();
            byte[] beforeMeta = TileEntityCodec.encode(beforeState);
            org.bukkit.block.data.BlockData target = BlockCodec.decode(change.targetBlock());
            if (target == null) {
                return null;
            }
            block.setBlockData(target, request.applyPhysics);
            if (change.targetMeta() != null && !TileEntityCodec.apply(block.getState(false), change.targetMeta())) {
                throw new IllegalStateException("tile entity restore failed");
            }
            return new AppliedChange(change.activityId(), change.worldUuid(), change.x(), change.y(), change.z(),
                    beforeData, target.getAsString(), beforeMeta, change.targetMeta(), null, null, -1, null, null,
                    null, null, false);
        }
        if (change.targetItem() != null || change.slot() >= 0) {
            Block block = world.getBlockAt(location);
            BlockState state = block.getState(false);
            if (!(state instanceof Container container)) {
                return null;
            }
            if (change.slot() < 0 || change.slot() >= container.getInventory().getSize()) {
                return null;
            }
            ItemStack before = container.getInventory().getItem(change.slot());
            ItemStack target = ItemCodec.decode(change.targetItem());
            container.getInventory().setItem(change.slot(), target);
            state.update(true, false);
            return new AppliedChange(change.activityId(), change.worldUuid(), change.x(), change.y(), change.z(),
                    null, null, null, null, ItemCodec.encode(before), change.targetItem(), change.slot(), null, null,
                    null, null, false);
        }
        if (change.targetText() != null) {
            Block block = world.getBlockAt(location);
            BlockState state = block.getState(false);
            if (!(state instanceof Sign sign)) {
                return null;
            }
            String beforeText = signText(sign);
            byte[] beforeMeta = TileEntityCodec.encode(sign);
            if (change.targetMeta() != null && !TileEntityCodec.apply(sign, change.targetMeta())) {
                throw new IllegalStateException("sign tile restore failed");
            }
            // Text is the authoritative line payload; old/migrated tile meta may not contain lines.
            applyText(sign, change.targetText());
            sign.update(true, false);
            return new AppliedChange(change.activityId(), change.worldUuid(), change.x(), change.y(), change.z(),
                    null, null, beforeMeta, change.targetMeta(), null, null, -1,
                    beforeText, change.targetText(), null, null, false);
        }
        if (change.entityType() != null || change.entityUuid() != null) {
            Entity existing = findEntity(world, change.entityUuid());
            if (change.removeEntity()) {
                if (existing != null && !Bukkit.isOwnedByCurrentRegion(existing)) {
                    return null;
                }
                if (existing != null) {
                    UUID uuid = existing.getUniqueId();
                    existing.remove();
                    return new AppliedChange(change.activityId(), change.worldUuid(), change.x(), change.y(),
                            change.z(), null, null, change.targetMeta(), null, null, null, -1, null, null,
                            change.entityType(), uuid, true);
                }
                return null;
            }
            Entity spawned = null;
            if (change.targetMeta() != null && change.targetMeta().length > 0) {
                try {
                    spawned = org.bukkit.Bukkit.getUnsafe()
                            .deserializeEntity(change.targetMeta(), world, true, true);
                } catch (RuntimeException ignored) {
                    // 数据损坏或版本差异时回退到只恢复实体类型
                }
            }
            if (spawned == null) {
                EntityType type = EntityType.fromName(change.entityType() == null ? ""
                        : change.entityType().replace("minecraft:", ""));
                if (type == null) {
                    return null;
                }
                spawned = world.spawnEntity(location, type);
            }
            return new AppliedChange(change.activityId(), change.worldUuid(), change.x(), change.y(), change.z(),
                    null, null, null, change.targetMeta(), null, null, -1, null, null, change.entityType(),
                    spawned.getUniqueId(), false);
        }
        return null;
    }

    private void finish(UUID actor, RollbackRequest request, RollbackResult result,
            CompletableFuture<RollbackResult> future, boolean rememberUndo) {
        result.completed = result.failed == 0;
        if (actor != null) {
            running.remove(actor);
            if (rememberUndo && !result.changes.isEmpty()) {
                plugin.undo().remember(actor, result.changes);
            }
        }
        future.complete(result);
    }

    private long budgetNanos() {
        MikuConfig config = plugin.config();
        double tick = Bukkit.getAverageTickTime();
        long budgetMs = config.rollbackBudgetMs;
        if (tick > 0) {
            budgetMs = Math.round(config.rollbackBudgetMs + config.rollbackMaxBudgetMs - tick);
        }
        budgetMs = Math.max(config.rollbackMinBudgetMs, Math.min(config.rollbackMaxBudgetMs, budgetMs));
        return budgetMs * 1_000_000L;
    }

    private static Entity findEntity(World world, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Entity direct = Bukkit.getEntity(uuid);
        if (direct != null) {
            return direct;
        }
        if (Scheduler.folia()) {
            // 跨区域遍历实体列表在 Folia 上不安全，UUID 未命中就放弃
            return null;
        }
        for (Entity entity : world.getEntities()) {
            if (entity.getUniqueId().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }

    private static void applyText(Sign sign, String text) {
        String[] lines = text == null ? new String[0] : text.split("\n", -1);
        for (int index = 0; index < 4; index++) {
            sign.getSide(Side.FRONT).setLine(index, index < lines.length ? lines[index] : "");
            sign.getSide(Side.BACK).setLine(index, index + 4 < lines.length ? lines[index + 4] : "");
        }
    }

    private static String signText(Sign sign) {
        return String.join("\n", sign.getSide(Side.FRONT).getLines())
                + "\n" + String.join("\n", sign.getSide(Side.BACK).getLines());
    }

    private static final UUID CONSOLE_ACTOR = new UUID(0L, 0L);

    private static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_ACTOR;
    }

    private record ChunkKey(UUID worldUuid, int chunkX, int chunkZ) {
    }

    private record PreviewSession(List<PlannedChange> planned) {
    }

    private record PlannedChange(long activityId, UUID worldUuid, int x, int y, int z, String targetBlock,
            byte[] targetMeta, byte[] targetItem, int slot, String targetText, String entityType, UUID entityUuid,
            boolean removeEntity) {
    }

    private static final class ChunkWork {
        private final UUID worldUuid;
        private final int chunkX;
        private final int chunkZ;
        private final List<PlannedChange> changes = new ArrayList<>();
        private final List<Long> appliedIds = new ArrayList<>();
        private final List<Location> redstone = new ArrayList<>();
        private final RollbackResult result = new RollbackResult();
        private int index;

        private ChunkWork(UUID worldUuid, int chunkX, int chunkZ) {
            this.worldUuid = worldUuid;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}
