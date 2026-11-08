package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.command.LookupRenderer;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.StoredActivity;
import dev.junxiex.mikuprotect.util.Scheduler;
import dev.junxiex.mikuprotect.util.TextUtil;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Per-player inspector toggle. Right-clicking a block performs an exact-coordinate lookup of the
 * latest records and prints them in chat.
 */
public final class InspectionService {

    private final MikuProtectPlugin plugin;
    private final MikuConfig config;
    private final LookupService lookup;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public InspectionService(MikuProtectPlugin plugin, MikuConfig config, LookupService lookup) {
        this.plugin = plugin;
        this.config = config;
        this.lookup = lookup;
    }

    public boolean toggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (!enabled.add(uuid)) {
            enabled.remove(uuid);
            return false;
        }
        return true;
    }

    public boolean enabled(Player player) {
        return player != null && enabled.contains(player.getUniqueId());
    }

    public void inspect(Player player, Block block) {
        if (!enabled(player) || block == null || block.getWorld() == null) {
            return;
        }
        LookupQuery query = LookupQuery.all()
                .world(block.getWorld().getName())
                .center(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                .radius(0)
                .since(0L)
                .limit(5);
        lookup.lookup(query).thenAccept(rows -> Scheduler.global(() -> print(player, block, rows)))
                .exceptionally(error -> {
                    Scheduler.global(() -> player.sendMessage(TextUtil.color(
                            TextUtil.PREFIX + "&c检查失败: " + error.getMessage())));
                    return null;
                });
    }

    private void print(Player player, Block block, List<StoredActivity> rows) {
        String position = block.getWorld().getName() + " " + block.getX() + ", " + block.getY() + ", "
                + block.getZ();
        if (rows == null || rows.isEmpty()) {
            player.sendMessage(TextUtil.color(TextUtil.PREFIX + "&7" + position + " 没有记录"));
            return;
        }
        player.sendMessage(TextUtil.color(TextUtil.PREFIX + "&7检查 &f" + position + " &7最近 &f"
                + rows.size() + " &7条："));
        LookupRenderer renderer = new LookupRenderer(config);
        for (StoredActivity row : rows) {
            player.sendMessage(TextUtil.color(renderer.format(row)));
        }
    }
}
