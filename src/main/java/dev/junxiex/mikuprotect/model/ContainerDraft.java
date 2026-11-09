package dev.junxiex.mikuprotect.model;

import org.bukkit.inventory.ItemStack;

/** Immutable item snapshots for a single slot change; encoded by the writer thread. */
public record ContainerDraft(int slot, ItemStack before, ItemStack after, int beforeAmount, int afterAmount) {
}
