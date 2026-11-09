package dev.junxiex.mikuprotect.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class TextUtil {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static final String PREFIX = "&b[MikuProtect] &f";

    private TextUtil() {
    }

    public static Component component(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    public static String plain(String text) {
        return PLAIN.serialize(component(text));
    }

    public static String plain(Component component) {
        return PLAIN.serialize(component == null ? Component.empty() : component);
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
