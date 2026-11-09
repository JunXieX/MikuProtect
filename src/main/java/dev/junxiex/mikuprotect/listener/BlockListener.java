package dev.junxiex.mikuprotect.listener;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.service.AlertService;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.util.BlockCodec;
import dev.junxiex.mikuprotect.util.Scheduler;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * 方块类监听器。只在 MONITOR 阶段取快照并投递到 {@link RecordingService}，不接触存储层。
 *
 * <p>Folia：方块事件由拥有该方块的区域线程触发，事件对象与其中的 Block/BlockState 都归属当前区域。
 * 爆炸、结构生长、活塞这类一次涉及多个方块的场景先做区域归属判定，跨区域的方块直接跳过，
 * 需要延迟到下一拍的操作一律交给 {@link Scheduler}。</p>
 */
public final class BlockListener implements Listener {

    /** 空方块状态缓存，避免高频事件反复构造。 */
    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final BlockData FIRE = Material.FIRE.createBlockData();

    private final RecordingService recording;
    private final AlertService alerts;

    public BlockListener(RecordingService recording, AlertService alerts) {
        this.recording = recording;
        this.alerts = alerts;
    }

    // ---------------------------------------------------------------- 玩家破坏 / 放置

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        // 事件阶段方块还在，直接取旧状态与旧 tile 数据
        BlockState state = block.getState();
        push(block.getLocation(), ActionType.BLOCK_BREAK, event.getPlayer(), AIR, state.getBlockData(),
                null, state);
        if (alerts != null) {
            alerts.blockBreak(event.getPlayer(), block.getLocation(), state.getType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Location location = block.getLocation();
        Material placed = block.getType();
        BlockState replaced = event.getBlockReplacedState();
        BlockData before = replaced != null ? replaced.getBlockData() : null;
        Player player = event.getPlayer();
        // 箱子/告示牌等新 tile 实体在事件之后才创建，延后一拍再取新状态
        Scheduler.at(location, () -> {
            Block current = location.getBlock();
            if (current.getType() != placed) {
                return;
            }
            BlockState state = current.getState();
            push(location, ActionType.BLOCK_PLACE, player, state.getBlockData(), before,
                    state, replaced);
        });
    }

    // ---------------------------------------------------------------- 自然变化

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        push(block.getLocation(), ActionType.BLOCK_BURN, null, AIR, state.getBlockData(),
                null, state);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        BlockState oldState = block.getState();
        BlockState newState = event.getNewState();
        push(block.getLocation(), ActionType.BLOCK_FADE, null,
                newState != null ? newState.getBlockData() : AIR, oldState.getBlockData(),
                newState, oldState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        Block block = event.getBlock();
        BlockState oldState = block.getState();
        BlockState newState = event.getNewState();
        push(block.getLocation(), ActionType.BLOCK_FORM, null,
                newState != null ? newState.getBlockData() : null, oldState.getBlockData(),
                newState, oldState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        BlockState oldState = block.getState();
        BlockState newState = event.getNewState();
        push(block.getLocation(), ActionType.BLOCK_SPREAD, null,
                newState != null ? newState.getBlockData() : null, oldState.getBlockData(),
                newState, oldState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        push(block.getLocation(), ActionType.BLOCK_IGNITE, event.getPlayer(), FIRE, state.getBlockData(),
                null, state);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        Block block = event.getBlock();
        Location location = block.getLocation();
        if (!owned(location)) {
            return;
        }
        BlockState state = block.getState();
        push(location, ActionType.BLOCK_DECAY, null, AIR, state.getBlockData(), null, state);
    }

    // ---------------------------------------------------------------- 活塞

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        recordMove(event.getBlocks(), event.getDirection(), ActionType.BLOCK_PISTON_EXTEND);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        BlockFace direction = event.getDirection();
        // 收缩时方块被拉向活塞，位移方向与朝向相反
        recordMove(event.getBlocks(), direction != null ? direction.getOppositeFace() : null,
                ActionType.BLOCK_PISTON_RETRACT);
    }

    /**
     * 活塞位移逐方块记录两条：目标位置写入被推方块，原位置变为空气。
     * 两侧都记才能让回滚拿到完整的前后状态，活塞头属于技术方块不单独记录。
     */
    private void recordMove(List<Block> blocks, BlockFace direction, ActionType action) {
        if (blocks == null || blocks.isEmpty() || direction == null) {
            return;
        }
        for (Block block : blocks) {
            BlockData data = block.getBlockData();
            Location source = block.getLocation();
            Location destination = block.getRelative(direction).getLocation();
            // 潜影盒一类容器被推动时，tile 数据跟着方块走，目标位置要带上它
            BlockState dataMeta = block.getState();
            if (owned(destination)) {
                Block destinationBlock = destination.getBlock();
                push(destination, action, null, data, destinationBlock.getBlockData(),
                        dataMeta, destinationBlock.getState());
            }
            if (owned(source)) {
                push(source, action, null, AIR, data, null, dataMeta);
            }
        }
    }

    // ---------------------------------------------------------------- 桶

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        BlockData now = placedLiquid(event.getBucket());
        if (now == null) {
            return;
        }
        Block block = event.getBlock();
        BlockState state = block.getState();
        push(block.getLocation(), ActionType.BLOCK_BUCKET_EMPTY, event.getPlayer(), now, state.getBlockData(),
                null, state);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        push(block.getLocation(), ActionType.BLOCK_BUCKET_FILL, event.getPlayer(), AIR, state.getBlockData(),
                null, state);
    }

    /** 倒出的液体类型；鱼桶一类倒出的都是水。 */
    private static BlockData placedLiquid(Material bucket) {
        if (bucket == null) {
            return null;
        }
        return switch (bucket) {
            case WATER_BUCKET -> Material.WATER.createBlockData();
            case LAVA_BUCKET -> Material.LAVA.createBlockData();
            case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW.createBlockData();
            default -> bucket.name().endsWith("_BUCKET") ? Material.WATER.createBlockData() : null;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        org.bukkit.inventory.ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        recording.record(ActivityRecord.item(event.getBlock().getLocation(), ActionType.BLOCK_DISPENSE, null,
                item.getType().getKey().toString(), dev.junxiex.mikuprotect.util.ItemCodec.encode(item),
                item.getAmount()));
    }

    // ---------------------------------------------------------------- 爆炸

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        destroy(event.blockList(), event.getExplosionResult());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        destroy(event.blockList(), event.getExplosionResult());
    }

    private void destroy(List<Block> blocks, ExplosionResult result) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        if (result != null && result != ExplosionResult.DESTROY && result != ExplosionResult.DESTROY_WITH_DECAY) {
            return;
        }
        for (Block block : blocks) {
            Location location = block.getLocation();
            if (!owned(location)) {
                continue;
            }
            BlockState state = block.getState();
            push(location, ActionType.BLOCK_EXPLOSION, null, AIR, state.getBlockData(),
                    null, state);
        }
    }

    // ---------------------------------------------------------------- 实体改方块 / 液体流动

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Location location = block.getLocation();
        if (!owned(location)) {
            return;
        }
        BlockData now = event.getBlockData();
        BlockState state = block.getState();
        Entity entity = event.getEntity();
        Player player = entity instanceof Player ? (Player) entity : null;
        push(location, ActionType.BLOCK_ENTITY_CHANGE, player, now != null ? now : AIR, state.getBlockData(),
                null, state);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        Location location = to.getLocation();
        if (!owned(location)) {
            return;
        }
        // 液体流动触发极频繁，只取方块状态，不做 tile 快照
        push(location, ActionType.BLOCK_LIQUID_FLOW, null, event.getBlock().getBlockData(), to.getBlockData(),
                null, null);
    }

    // ---------------------------------------------------------------- 生长 / 催熟

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        applyStates(event.getBlocks(), ActionType.BLOCK_GROWTH, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        applyStates(event.getBlocks(), ActionType.BLOCK_FERTILIZE, event.getPlayer());
    }

    private void applyStates(List<BlockState> states, ActionType action, Player player) {
        if (states == null || states.isEmpty()) {
            return;
        }
        for (BlockState state : states) {
            if (state == null) {
                continue;
            }
            Location location = state.getLocation();
            if (!owned(location)) {
                continue;
            }
            BlockState oldState = location.getBlock().getState();
            push(location, action, player, state.getBlockData(), oldState.getBlockData(),
                    state, oldState);
        }
    }

    // ---------------------------------------------------------------- 内部

    private void push(Location location, ActionType action, Player player, BlockData now, BlockData before,
            BlockState metaState, BlockState oldMetaState) {
        if (location == null || location.getWorld() == null || action == null || (now == null && before == null)) {
            return;
        }
        // 破坏/爆炸后 now 是空气，材质应取变更前的方块，否则按材质查询会全部落到 air
        BlockData materialData = now != null && now.getMaterial() != Material.AIR ? now : before;
        ActivityRecord skeleton = ActivityRecord.block(location, action,
                player != null ? player.getName() : null,
                player != null ? player.getUniqueId() : null,
                BlockCodec.materialKey(materialData), BlockCodec.encode(now), BlockCodec.encode(before),
                null, null);
        recording.recordDeferredBlock(skeleton, metaState, oldMetaState);
    }

    /** 当前线程是否拥有该位置所在的区域；Paper 上恒为 true。 */
    private static boolean owned(Location location) {
        return location != null && location.getWorld() != null && Bukkit.isOwnedByCurrentRegion(location);
    }
}
