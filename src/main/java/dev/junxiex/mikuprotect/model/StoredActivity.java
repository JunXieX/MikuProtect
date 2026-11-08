package dev.junxiex.mikuprotect.model;

import java.util.List;
import java.util.UUID;

/**
 * A persisted activity returned by queries.
 */
public record StoredActivity(
        long id,
        long time,
        UUID worldUuid,
        String worldName,
        int x,
        int y,
        int z,
        ActionType action,
        String userName,
        UUID userUuid,
        String material,
        String blockData,
        String oldBlockData,
        byte[] itemData,
        int amount,
        String entityType,
        UUID entityUuid,
        String targetUser,
        String message,
        byte[] meta,
        byte[] oldMeta,
        boolean rolledBack,
        List<StoredContainer> containers) {

    public record StoredContainer(int slot, byte[] beforeData, byte[] afterData, int beforeAmount, int afterAmount) {
    }
}
