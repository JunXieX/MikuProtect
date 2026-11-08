package dev.junxiex.mikuprotect.model;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * An immutable record waiting to be persisted. Storage assigns {@link #id} during the batch write.
 *
 * <p>For block changes {@link #blockData} is the state after the change and {@link #oldBlockData}
 * the state before it. {@link #meta} and {@link #oldMeta} carry the tile-entity payloads (container
 * contents, sign text, spawner settings) for full fidelity restores.</p>
 */
public final class ActivityRecord {

    public long id;
    public final long time;
    public final UUID worldUuid;
    public final String worldName;
    public final int x;
    public final int y;
    public final int z;
    public final ActionType action;
    public final String userName;
    public final UUID userUuid;
    public final String material;
    public final String blockData;
    public final String oldBlockData;
    public final byte[] itemData;
    public final int amount;
    public final String entityType;
    public final UUID entityUuid;
    public final String targetUser;
    public final String message;
    public final byte[] meta;
    public final byte[] oldMeta;
    public final List<ContainerChange> containers;

    private ActivityRecord(long time, UUID worldUuid, String worldName, int x, int y, int z, ActionType action,
            String userName, UUID userUuid, String material, String blockData, String oldBlockData, byte[] itemData,
            int amount, String entityType, UUID entityUuid, String targetUser, String message, byte[] meta,
            byte[] oldMeta, List<ContainerChange> containers) {
        this.time = time;
        this.worldUuid = worldUuid;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.userName = userName;
        this.userUuid = userUuid;
        this.material = material;
        this.blockData = blockData;
        this.oldBlockData = oldBlockData;
        this.itemData = itemData;
        this.amount = amount;
        this.entityType = entityType;
        this.entityUuid = entityUuid;
        this.targetUser = targetUser;
        this.message = message;
        this.meta = meta;
        this.oldMeta = oldMeta;
        this.containers = containers == null ? List.of() : List.copyOf(containers);
    }

    public static ActivityRecord block(Location location, ActionType action, Player player, BlockData now,
            BlockData before) {
        return block(location, action, player, now, before, null, null);
    }

    public static ActivityRecord block(Location location, ActionType action, Player player, BlockData now,
            BlockData before, byte[] meta, byte[] oldMeta) {
        World world = location.getWorld();
        String material = now != null ? now.getMaterial().getKey().toString() : null;
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                player != null ? player.getName() : null, player != null ? player.getUniqueId() : null,
                material, now != null ? now.getAsString() : null,
                before != null ? before.getAsString() : null, null, 1, null, null, null, null, meta, oldMeta,
                List.of());
    }

    public static ActivityRecord block(Location location, ActionType action, String user, UUID userUuid,
            String material, String now, String before) {
        return block(location, action, user, userUuid, material, now, before, null, null);
    }

    public static ActivityRecord block(Location location, ActionType action, String user, UUID userUuid,
            String material, String now, String before, byte[] meta, byte[] oldMeta) {
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                user, userUuid, material, now, before, null, 1, null, null, null, null, meta, oldMeta, List.of());
    }

    public static ActivityRecord container(Location location, ActionType action, Player player,
            List<ContainerChange> changes) {
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                player != null ? player.getName() : null, player != null ? player.getUniqueId() : null,
                null, null, null, null, 0, null, null, null, null, null, null, changes);
    }

    public static ActivityRecord entity(Location location, ActionType action, Player player, EntityType type,
            UUID entityUuid, byte[] data) {
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                player != null ? player.getName() : null, player != null ? player.getUniqueId() : null,
                null, null, null, null, 1, type != null ? type.getKey().toString() : null, entityUuid,
                null, null, data, null, List.of());
    }

    public static ActivityRecord entity(Location location, ActionType action, String user, UUID userUuid,
            EntityType type, UUID entityUuid, String targetUser, byte[] data) {
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                user, userUuid, null, null, null, null, 1,
                type != null ? type.getKey().toString() : null, entityUuid, targetUser, null, data, null, List.of());
    }

    public static ActivityRecord nameChange(Player player, String oldName) {
        Location location = player.getLocation();
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), ActionType.PLAYER_NAME_CHANGE,
                player.getName(), player.getUniqueId(), null, null, null, null, 1,
                null, null, oldName, player.getName(), null, null, List.of());
    }

    public static ActivityRecord item(Location location, ActionType action, Player player, String material,
            byte[] data, int amount) {
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                player != null ? player.getName() : null, player != null ? player.getUniqueId() : null,
                material, null, null, data, amount, null, null, null, null, null, null, List.of());
    }

    public static ActivityRecord message(Location location, ActionType action, Player player, String message,
            byte[] meta) {
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                player != null ? player.getName() : null, player != null ? player.getUniqueId() : null,
                null, null, null, null, 1, null, null, null,
                message == null ? null : message.substring(0, Math.min(message.length(), 16000)), meta, null, List.of());
    }

    public static ActivityRecord sign(Location location, Player player, String oldLines, String newLines,
            byte[] oldMeta, byte[] newMeta) {
        World world = location.getWorld();
        String material = location.getBlock().getType().getKey().toString();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), ActionType.SIGN_CHANGE,
                player != null ? player.getName() : null, player != null ? player.getUniqueId() : null,
                material, newLines, oldLines, null, 1, null, null, null, null, newMeta, oldMeta, List.of());
    }

    public static ActivityRecord session(Player player, ActionType action) {
        Location location = player.getLocation();
        World world = location.getWorld();
        return new ActivityRecord(System.currentTimeMillis(), world.getUID(), world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), action,
                player.getName(), player.getUniqueId(), null, null, null, null, 1,
                null, null, null, null, null, null, List.of());
    }

    /**
     * Rebuilds a record read back from the write-ahead log. The id is left at zero so storage
     * allocates a fresh primary key on replay.
     */
    public static ActivityRecord restored(long time, UUID worldUuid, String worldName, int x, int y, int z,
            ActionType action, String userName, UUID userUuid, String material, String blockData,
            String oldBlockData, byte[] itemData, int amount, String entityType, UUID entityUuid,
            String targetUser, String message, byte[] meta, byte[] oldMeta, List<ContainerChange> containers) {
        return new ActivityRecord(time, worldUuid, worldName, x, y, z, action, userName, userUuid, material,
                blockData, oldBlockData, itemData, amount, entityType, entityUuid, targetUser, message, meta,
                oldMeta, containers);
    }

    public ActivityRecord withContainers(List<ContainerChange> changes) {
        return new ActivityRecord(time, worldUuid, worldName, x, y, z, action, userName, userUuid, material,
                blockData, oldBlockData, itemData, amount, entityType, entityUuid, targetUser, message, meta,
                oldMeta, changes);
    }

    public ActivityRecord withItem(byte[] data, int itemAmount) {
        return new ActivityRecord(time, worldUuid, worldName, x, y, z, action, userName, userUuid, material,
                blockData, oldBlockData, data, itemAmount, entityType, entityUuid, targetUser, message, meta,
                oldMeta, containers);
    }

    public boolean isContainer() {
        return action.category() == ActionType.Category.CONTAINER;
    }

    public List<ContainerChange> containersOrEmpty() {
        return containers;
    }
}
