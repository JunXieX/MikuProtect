package dev.junxiex.mikuprotect.listener;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.service.RecordingService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;

/**
 * 实体类监听器。实体事件都发生在实体所属的区域线程上，只读事件对象本身，不跨区域访问方块。
 *
 * <p>生成事件全量记录，是否保留交给配置里的 recording.disabled-actions（entity-spawn）；
 * 实体爆炸的方块部分由 BlockListener 负责，这里只记实体侧。</p>
 */
public final class EntityListener implements Listener {

    private final RecordingService recording;
    private final Server server;

    public EntityListener(RecordingService recording, Server server) {
        this.recording = recording;
        this.server = server;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        entity(event.getLocation(), ActionType.ENTITY_SPAWN, null, event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            // 玩家死亡由 PlayerDeathEvent 单独记录
            return;
        }
        entity(entity.getLocation(), ActionType.ENTITY_KILL, entity.getKiller(), entity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        entity(player.getLocation(), ActionType.PLAYER_DEATH, player, player);
        Player killer = player.getKiller();
        if (killer != null) {
            entity(player.getLocation(), ActionType.PLAYER_KILL, killer, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        entity(event.getLocation(), ActionType.ENTITY_EXPLODE, null, event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        Entity entity = event.getEntity();
        entity(entity.getLocation(), ActionType.ENTITY_TRANSFORM, null, entity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent event) {
        entity(event.getBlock().getLocation(), ActionType.ENTITY_PLACE, event.getPlayer(), event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        entity(clicked.getLocation(), ActionType.ENTITY_INTERACT, event.getPlayer(), clicked);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        entity(event.getEntity().getLocation(), ActionType.ENTITY_SHEAR, event.getPlayer(), event.getEntity());
    }

    private void entity(Location location, ActionType action, Player player, Entity entity) {
        if (location == null || location.getWorld() == null || entity == null || action == null) {
            return;
        }
        byte[] snapshot = null;
        // Only ENTITY_KILL needs the payload to respawn the entity; spawn/interact are high volume.
        if (action == ActionType.ENTITY_KILL && !(entity instanceof Player)) {
            try {
                snapshot = server.getUnsafe().serializeEntity(entity);
            } catch (RuntimeException ignored) {
                // 某些实体在事件阶段已不可序列化；记录类型与 UUID 仍然有价值
            }
        }
        recording.record(ActivityRecord.entity(location, action, player, entity.getType(), entity.getUniqueId(), snapshot));
    }
}
