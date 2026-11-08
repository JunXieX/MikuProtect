package dev.junxiex.mikuprotect.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ItemCodec {

    private ItemCodec() {
    }

    public static byte[] encode(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        return stack.serializeAsBytes();
    }

    public static ItemStack decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(data);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid item data", exception);
        }
    }

    public static int amount(ItemStack stack) {
        return stack == null ? 0 : stack.getAmount();
    }
}
