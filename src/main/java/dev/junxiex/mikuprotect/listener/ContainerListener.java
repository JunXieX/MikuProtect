package dev.junxiex.mikuprotect.listener;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ContainerDraft;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.util.ItemCodec;
import dev.junxiex.mikuprotect.util.Scheduler;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 容器类监听器。只记录有方块位置的容器，玩家背包一律跳过。
 *
 * <p>点击/拖拽在事件发生时先抓旧物品，再用 {@link Scheduler#entityDelayed} 在下一拍按方块重新取容器库存
 * 做比对；漏斗搬运则比对来源与目标两个方块的整箱快照。</p>
 */
public final class ContainerListener implements Listener {

    /** 整箱快照的槽位上限，防止第三方插件塞入超大自定义库存。 */
    private static final int MAX_SNAPSHOT_SLOTS = 256;

    private final RecordingService recording;

    public ContainerListener(RecordingService recording) {
        this.recording = recording;
    }

    /** 单槽快照；只有真正变化的槽位才会序列化 ItemStack。 */
    private record Snapshot(int slot, ItemStack item) {
    }

    // ---------------------------------------------------------------- 点击 / 拖拽

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Player) {
            return;
        }
        Location location = top.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        List<Snapshot> before;
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            // Double-click collection can pull matching items from every top slot.
            before = snapshot(top);
        } else if (clicked == top) {
            int slot = event.getSlot();
            if (slot < 0 || slot >= top.getSize()) {
                return;
            }
            before = List.of(new Snapshot(slot, clone(event.getCurrentItem())));
        } else if (event.isShiftClick()) {
            // Shift-click from the player inventory can fill any top slot; snapshot the top before it moves.
            before = snapshot(top);
        } else {
            return;
        }
        Scheduler.entityDelayed(player, () -> capture(location, player, before), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Player) {
            return;
        }
        Location location = top.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        int topSize = top.getSize();
        List<Snapshot> before = new ArrayList<>(event.getRawSlots().size());
        for (int rawSlot : event.getRawSlots()) {
            // 原始槽位小于上方容器大小才属于容器，其余是玩家背包
            if (rawSlot < 0 || rawSlot >= topSize) {
                continue;
            }
            ItemStack current = top.getItem(rawSlot);
            before.add(new Snapshot(rawSlot, clone(current)));
        }
        if (before.isEmpty()) {
            return;
        }
        Scheduler.entityDelayed(player, () -> capture(location, player, before), 1L);
    }

    // ---------------------------------------------------------------- 漏斗等搬运

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveItem(InventoryMoveItemEvent event) {
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();
        if (source == null || destination == null || source.getHolder() instanceof Player
                || destination.getHolder() instanceof Player) {
            return;
        }
        Location sourceLocation = source.getLocation();
        Location destinationLocation = destination.getLocation();
        if (sourceLocation == null || destinationLocation == null
                || sourceLocation.getWorld() == null || destinationLocation.getWorld() == null) {
            return;
        }
        List<Snapshot> sourceBefore = snapshot(source);
        List<Snapshot> destinationBefore = snapshot(destination);
        regionLater(sourceLocation, () -> compare(sourceLocation, null, sourceBefore));
        regionLater(destinationLocation, () -> compare(destinationLocation, null, destinationBefore));
    }

    // ---------------------------------------------------------------- 比对

    /** 在容器方块所属区域执行比对，跨区域时切换到方块所在区域。 */
    private void capture(Location location, Player player, List<Snapshot> before) {
        if (!Scheduler.owned(location)) {
            Scheduler.at(location, () -> compare(location, player, before));
            return;
        }
        compare(location, player, before);
    }

    private void compare(Location location, Player player, List<Snapshot> before) {
        Inventory inventory = containerAt(location);
        if (inventory == null) {
            return;
        }
        int size = inventory.getSize();
        List<ContainerDraft> changes = new ArrayList<>(before.size());
        int delta = 0;
        for (Snapshot snapshot : before) {
            int slot = snapshot.slot();
            if (slot < 0 || slot >= size) {
                continue;
            }
            ItemStack after = inventory.getItem(slot);
            ItemStack beforeItem = snapshot.item();
            int beforeAmount = ItemCodec.amount(beforeItem);
            int afterAmount = ItemCodec.amount(after);
            if (sameItem(beforeItem, after) && beforeAmount == afterAmount) {
                continue;
            }
            changes.add(new ContainerDraft(slot, beforeItem, after == null ? null : after.clone(),
                    beforeAmount, afterAmount));
            delta += afterAmount - beforeAmount;
        }
        if (changes.isEmpty()) {
            return;
        }
        ActionType action = delta < 0 ? ActionType.CONTAINER_REMOVE : ActionType.CONTAINER_ADD;
        recording.recordDeferredContainer(location, action, player, changes);
    }

    private static List<Snapshot> snapshot(Inventory inventory) {
        int size = Math.min(inventory.getSize(), MAX_SNAPSHOT_SLOTS);
        List<Snapshot> snapshots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            snapshots.add(new Snapshot(slot, clone(inventory.getItem(slot))));
        }
        return snapshots;
    }

    private static ItemStack clone(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static boolean sameItem(ItemStack left, ItemStack right) {
        if (left == null) {
            return right == null;
        }
        return right != null && left.isSimilar(right);
    }

    private static Inventory containerAt(Location location) {
        // A live state avoids copying the whole inventory just to read the current slots.
        BlockState state = location.getBlock().getState(false);
        return state instanceof InventoryHolder holder ? holder.getInventory() : null;
    }

    /** 下一拍在方块所属区域执行；无插件实例时退回区域调度器。 */
    private static void regionLater(Location location, Runnable task) {
        Plugin plugin = Scheduler.plugin();
        if (plugin == null) {
            Scheduler.at(location, task);
            return;
        }
        plugin.getServer().getRegionScheduler().runDelayed(plugin, location, scheduled -> task.run(), 1L);
    }
}
