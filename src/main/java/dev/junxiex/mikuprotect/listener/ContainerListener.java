package dev.junxiex.mikuprotect.listener;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.util.ItemCodec;
import dev.junxiex.mikuprotect.util.Scheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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

    /** 单槽快照：事件时刻的物品字节与数量，延迟任务只负责比对。 */
    private record Snapshot(int slot, byte[] data, int amount) {
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
        Location location = clicked.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        int slot = event.getSlot();
        if (slot < 0) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        List<Snapshot> before = List.of(new Snapshot(slot, ItemCodec.encode(current), ItemCodec.amount(current)));
        Scheduler.entityDelayed(player, () -> capture(location, player, before), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
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
            before.add(new Snapshot(rawSlot, ItemCodec.encode(current), ItemCodec.amount(current)));
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
        Location sourceLocation = source != null ? source.getLocation() : null;
        Location destinationLocation = destination != null ? destination.getLocation() : null;
        if (sourceLocation == null || destinationLocation == null
                || sourceLocation.getWorld() == null || destinationLocation.getWorld() == null) {
            return;
        }
        List<Snapshot> sourceBefore = snapshot(source);
        List<Snapshot> destinationBefore = snapshot(destination);
        regionLater(sourceLocation, () -> {
            compare(sourceLocation, null, sourceBefore);
            if (Bukkit.isOwnedByCurrentRegion(destinationLocation)) {
                compare(destinationLocation, null, destinationBefore);
            }
        });
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
        List<ContainerChange> changes = new ArrayList<>(before.size());
        int delta = 0;
        for (Snapshot snapshot : before) {
            int slot = snapshot.slot();
            if (slot < 0 || slot >= size) {
                continue;
            }
            ItemStack after = inventory.getItem(slot);
            byte[] afterData = ItemCodec.encode(after);
            int afterAmount = ItemCodec.amount(after);
            if (Arrays.equals(snapshot.data(), afterData) && snapshot.amount() == afterAmount) {
                continue;
            }
            changes.add(new ContainerChange(slot, snapshot.data(), afterData, snapshot.amount(), afterAmount));
            delta += afterAmount - snapshot.amount();
        }
        if (changes.isEmpty()) {
            return;
        }
        ActionType action = delta < 0 ? ActionType.CONTAINER_REMOVE : ActionType.CONTAINER_ADD;
        recording.record(ActivityRecord.container(location, action, player, changes));
    }

    private static List<Snapshot> snapshot(Inventory inventory) {
        int size = Math.min(inventory.getSize(), MAX_SNAPSHOT_SLOTS);
        List<Snapshot> snapshots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            snapshots.add(new Snapshot(slot, ItemCodec.encode(stack), ItemCodec.amount(stack)));
        }
        return snapshots;
    }

    private static Inventory containerAt(Location location) {
        BlockState state = location.getBlock().getState();
        return state instanceof InventoryHolder holder ? holder.getInventory() : null;
    }

    /** 下一拍在方块所属区域执行；无插件实例时退回区域调度器。 */
    private static void regionLater(Location location, Runnable task) {
        Plugin plugin = Scheduler.plugin();
        if (plugin == null) {
            Scheduler.at(location, task);
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, location, scheduled -> task.run(), 1L);
    }
}
