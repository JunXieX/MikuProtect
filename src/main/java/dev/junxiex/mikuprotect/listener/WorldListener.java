package dev.junxiex.mikuprotect.listener;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.util.Scheduler;
import dev.junxiex.mikuprotect.util.BlockCodec;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.world.PortalCreateEvent;

/**
 * 世界类监听器：传送门生成与海绵吸水。
 *
 * <p>两个事件都按 BlockState 列表逐方块记录，先判定区域归属再读取方块，跨区域的方块跳过。</p>
 */
public final class WorldListener implements Listener {

    private static final BlockData AIR = Material.AIR.createBlockData();

    private final RecordingService recording;

    public WorldListener(RecordingService recording) {
        this.recording = recording;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        Player player = event.getEntity() instanceof Player ? (Player) event.getEntity() : null;
        for (BlockState state : event.getBlocks()) {
            if (state == null) {
                continue;
            }
            Location location = state.getLocation();
            if (!Scheduler.owned(location)) {
                continue;
            }
            BlockState oldState = location.getBlock().getState();
            push(location, ActionType.WORLD_PORTAL, player, state.getBlockData(), oldState.getBlockData(),
                    state, oldState);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        for (BlockState state : event.getBlocks()) {
            if (state == null) {
                continue;
            }
            Location location = state.getLocation();
            if (!Scheduler.owned(location)) {
                continue;
            }
            // 被吸收的液体方块变成空气
            BlockState oldState = location.getBlock().getState();
            push(location, ActionType.BLOCK_ENTITY_CHANGE, null, AIR, oldState.getBlockData(),
                    null, oldState);
        }
    }

    private void push(Location location, ActionType action, Player player, BlockData now, BlockData before,
            BlockState metaState, BlockState oldMetaState) {
        if (location == null || location.getWorld() == null || action == null || (now == null && before == null)) {
            return;
        }
        org.bukkit.block.data.BlockData materialData =
                now != null && now.getMaterial() != Material.AIR ? now : before;
        ActivityRecord skeleton = ActivityRecord.block(location, action,
                player != null ? player.getName() : null,
                player != null ? player.getUniqueId() : null,
                BlockCodec.materialKey(materialData), BlockCodec.encode(now), BlockCodec.encode(before),
                null, null);
        recording.recordDeferredBlock(skeleton, metaState, oldMetaState);
    }

}
