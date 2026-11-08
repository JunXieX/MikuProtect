package dev.junxiex.mikuprotect.util;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

public final class BlockCodec {

    private BlockCodec() {
    }

    public static String encode(BlockData data) {
        return data == null ? null : data.getAsString();
    }

    public static BlockData decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Bukkit.createBlockData(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String materialKey(BlockData data) {
        return data == null ? null : data.getMaterial().getKey().toString();
    }
}
