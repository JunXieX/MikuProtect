package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.util.TextUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class AlertService {

    private final MikuProtectPlugin plugin;
    private final MikuConfig config;

    public AlertService(MikuProtectPlugin plugin, MikuConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void blockBreak(Player player, Location location, Material material) {
        if (!config.alertsEnabled || player == null || material == null || !config.alertBlocks.contains(material)) {
            return;
        }
        String message = TextUtil.PREFIX + "&e" + player.getName() + " &7broke &f" + material.name()
                + " &7at &f" + location.getWorld().getName() + " " + location.getBlockX() + ", "
                + location.getBlockY() + ", " + location.getBlockZ();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission(config.alertPermission) && !online.equals(player)) {
                online.sendMessage(TextUtil.component(message));
            }
        }
        plugin.getLogger().info(TextUtil.plain(message));
    }
}
