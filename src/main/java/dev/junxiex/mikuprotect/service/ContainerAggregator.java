package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Merges repeated container slot changes for the same block/action/user inside a short window.
 *
 * <p>Hopper chains and repeated shift-clicks otherwise produce one activity row per event. The
 * merged record keeps the first "before" and the last "after" per slot, so rollback still restores
 * the exact state at the start of the window.</p>
 */
final class ContainerAggregator {

    private record Key(UUID worldUuid, int x, int y, int z, ActionType action, String user) {
    }

    private static final class Entry {
        private final ActivityRecord template;
        private final Map<Integer, ContainerChange> changes = new LinkedHashMap<>();
        private final long createdAt;

        private Entry(ActivityRecord template, long createdAt) {
            this.template = template;
            this.createdAt = createdAt;
        }
    }

    private final ConcurrentHashMap<Key, Entry> pending = new ConcurrentHashMap<>();
    private final long windowMs;

    ContainerAggregator(long windowMs) {
        this.windowMs = Math.max(100L, windowMs);
    }

    boolean offer(ActivityRecord record) {
        if (record == null || record.action == null || record.action.category() != ActionType.Category.CONTAINER
                || record.containers.isEmpty()) {
            return false;
        }
        Key key = new Key(record.worldUuid, record.x, record.y, record.z, record.action, record.userName);
        long now = System.currentTimeMillis();
        pending.compute(key, (ignored, existing) -> {
            Entry entry = existing == null ? new Entry(record, now) : existing;
            for (ContainerChange change : record.containers) {
                merge(entry.changes, change);
            }
            return entry.changes.isEmpty() ? null : entry;
        });
        return true;
    }

    void flush(Consumer<ActivityRecord> sink, boolean all) {
        long now = System.currentTimeMillis();
        for (Map.Entry<Key, Entry> entry : pending.entrySet()) {
            Entry value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!all && now - value.createdAt < windowMs) {
                continue;
            }
            if (pending.remove(entry.getKey(), value)) {
                sink.accept(value.template.withContainers(new ArrayList<>(value.changes.values())));
            }
        }
    }

    private static void merge(Map<Integer, ContainerChange> changes, ContainerChange change) {
        if (change == null) {
            return;
        }
        ContainerChange existing = changes.get(change.slot());
        if (existing == null) {
            changes.put(change.slot(), change);
            return;
        }
        ContainerChange merged = new ContainerChange(change.slot(), existing.beforeData(), change.afterData(),
                existing.beforeAmount(), change.afterAmount());
        if (Arrays.equals(merged.beforeData(), merged.afterData())
                && merged.beforeAmount() == merged.afterAmount()) {
            changes.remove(change.slot());
        } else {
            changes.put(change.slot(), merged);
        }
    }
}
