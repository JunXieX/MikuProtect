package dev.junxiex.mikuprotect.model;

import java.util.UUID;

/**
 * A single world change performed by a rollback/restore, used for preview apply/cancel and undo.
 */
public record AppliedChange(
        long activityId,
        UUID worldUuid,
        int x,
        int y,
        int z,
        String beforeBlockData,
        String afterBlockData,
        byte[] beforeMeta,
        byte[] afterMeta,
        byte[] beforeItem,
        byte[] afterItem,
        int slot,
        String beforeText,
        String afterText,
        String entityType,
        UUID entityUuid,
        boolean removeEntity) {

}
