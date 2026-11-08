package dev.junxiex.mikuprotect.listener;

import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.service.InspectionService;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.util.ItemCodec;
import dev.junxiex.mikuprotect.util.Scheduler;
import dev.junxiex.mikuprotect.util.TileEntityCodec;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.EnderChest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家类监听器：上下线、聊天、命令、告示牌、丢弃与拾取。
 *
 * <p>聊天与命令只取纯文本并截断到 config.maxMessageLength。聊天事件在异步线程触发，
 * 这里只读不可变的事件数据与玩家位置快照，不访问任何方块或库存。</p>
 */
public final class PlayerListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final int SIGN_LINES = 4;

    private final RecordingService recording;
    private final MikuConfig config;
    private final Storage storage;
    private final InspectionService inspection;

    public PlayerListener(RecordingService recording, MikuConfig config, Storage storage,
            InspectionService inspection) {
        this.recording = recording;
        this.config = config;
        this.storage = storage;
        this.inspection = inspection;
    }

    // ---------------------------------------------------------------- 上下线

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (storage != null) {
            String previous = storage.userName(player.getUniqueId());
            if (previous != null && !previous.equals(player.getName())) {
                recording.record(ActivityRecord.nameChange(player, previous));
            }
        }
        session(player, ActionType.PLAYER_JOIN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        session(event.getPlayer(), ActionType.PLAYER_QUIT);
    }

    private void session(Player player, ActionType action) {
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        recording.record(ActivityRecord.session(player, action));
    }

    // ---------------------------------------------------------------- 聊天 / 命令

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = PLAIN.serialize(event.message());
        // AsyncChatEvent is off the region thread; read the player position on its entity scheduler.
        Scheduler.entity(player, () -> message(player, ActionType.PLAYER_CHAT, text));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        message(event.getPlayer(), ActionType.PLAYER_COMMAND, event.getMessage());
    }

    private void message(Player player, ActionType action, String text) {
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        recording.record(ActivityRecord.message(location, action, player, truncate(text), null));
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= config.maxMessageLength ? text : text.substring(0, config.maxMessageLength);
    }

    // ---------------------------------------------------------------- 告示牌

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Block block = event.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        Side side = event.getSide();
        List<Component> front = sign.getSide(Side.FRONT).lines();
        List<Component> back = sign.getSide(Side.BACK).lines();
        List<Component> edited = event.lines();
        String oldLines = join(front, back);
        String newLines = side == Side.BACK ? join(front, edited) : join(edited, back);
        // 旧 tile 数据来自现场告示牌，新 tile 数据由旧快照改写被编辑的一面得到
        byte[] oldMeta = TileEntityCodec.encode(state);
        byte[] newMeta = TileEntityCodec.encode(withLines(state, side, edited));
        recording.record(ActivityRecord.sign(block.getLocation(), event.getPlayer(), oldLines, newLines,
                oldMeta, newMeta));
    }

    private static BlockState withLines(BlockState state, Side side, List<Component> lines) {
        BlockState copy = state.copy();
        if (!(copy instanceof Sign sign) || side == null || lines == null) {
            return copy;
        }
        SignSide target = sign.getSide(side);
        for (int index = 0; index < SIGN_LINES && index < lines.size(); index++) {
            Component line = lines.get(index);
            target.line(index, line == null ? Component.empty() : line);
        }
        return copy;
    }

    /** 正反两面各 4 行，按 8 行拼接便于查询与回滚。 */
    private static String join(List<Component> front, List<Component> back) {
        StringBuilder builder = new StringBuilder();
        appendLines(builder, front);
        builder.append('\n');
        appendLines(builder, back);
        return builder.toString();
    }

    private static void appendLines(StringBuilder builder, List<Component> lines) {
        for (int index = 0; index < SIGN_LINES; index++) {
            if (index > 0) {
                builder.append('\n');
            }
            Component line = lines != null && index < lines.size() ? lines.get(index) : null;
            builder.append(line == null ? "" : PLAIN.serialize(line));
        }
    }

    // ---------------------------------------------------------------- 容器打开

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (inspection != null && inspection.enabled(event.getPlayer())
                && event.getPlayer().hasPermission(dev.junxiex.mikuprotect.Permissions.INSPECT)) {
            inspection.inspect(event.getPlayer(), block);
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof org.bukkit.inventory.InventoryHolder) && !(state instanceof EnderChest)) {
            return;
        }
        recording.record(ActivityRecord.block(block.getLocation(), ActionType.CONTAINER_OPEN, event.getPlayer(),
                state.getBlockData(), state.getBlockData()));
    }

    // ---------------------------------------------------------------- 物品

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item drop = event.getItemDrop();
        Location location = drop.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        ItemStack stack = drop.getItemStack().clone();
        recording.record(ActivityRecord.entity(location, ActionType.ITEM_DROP, event.getPlayer(), EntityType.ITEM,
                drop.getUniqueId(), null).withItem(ItemCodec.encode(stack), stack.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        ItemStack stack = item.getItemStack().clone();
        int picked = Math.max(0, stack.getAmount() - event.getRemaining());
        recording.record(ActivityRecord.entity(location, ActionType.ITEM_PICKUP, player, EntityType.ITEM,
                item.getUniqueId(), null).withItem(ItemCodec.encode(stack), picked));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getRecipe().getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }
        recordItem(player, ActionType.ITEM_CRAFT, result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemBreak(PlayerItemBreakEvent event) {
        ItemStack item = event.getBrokenItem();
        if (item != null) {
            recordItem(event.getPlayer(), ActionType.ITEM_BREAK, item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item != null) {
            recordItem(event.getPlayer(), ActionType.ITEM_CONSUME, item);
        }
    }

    private void recordItem(Player player, ActionType action, ItemStack item) {
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        ItemStack copy = item.clone();
        recording.record(ActivityRecord.item(location, action, player,
                copy.getType().getKey().toString(), ItemCodec.encode(copy), copy.getAmount()));
    }
}
