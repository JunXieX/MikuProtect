package dev.junxiex.mikuprotect.util;

import org.bukkit.ChatColor;

public final class TextUtil {

    public static final String PREFIX = ChatColor.AQUA + "[MikuProtect] " + ChatColor.WHITE;

    private TextUtil() {
    }

    public static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    public static String plain(String text) {
        return ChatColor.stripColor(color(text));
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
