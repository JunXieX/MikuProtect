package dev.junxiex.mikuprotect.model;

/**
 * A single slot diff inside a container transaction.
 *
 * @param slot the container slot index
 * @param beforeData serialized item before the change, null when empty
 * @param afterData serialized item after the change, null when empty
 * @param beforeAmount amount before the change
 * @param afterAmount amount after the change
 */
public record ContainerChange(int slot, byte[] beforeData, byte[] afterData, int beforeAmount, int afterAmount) {
}
