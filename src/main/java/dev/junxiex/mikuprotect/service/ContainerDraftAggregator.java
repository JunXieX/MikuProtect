package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import dev.junxiex.mikuprotect.model.ContainerDraft;
import dev.junxiex.mikuprotect.util.ItemCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Aggregates item snapshots before the writer encodes them. This keeps a hopper burst on a few
 * rows without serializing ItemStacks on the region thread.
 */
final class ContainerDraftAggregator {

    private record Key(UUID worldUuid, int x, int y, int z, ActionType action, String user) {
    }

    private static final class Entry {
        private final Location location;
        private final ActionType action;
        private final String user;
        private final UUID userUuid;
        private final Map<Integer, ContainerDraft> changes = new LinkedHashMap<>();
        private final long createdAt;

        private Entry(Location location, ActionType action, String user, UUID userUuid, long createdAt) {
            this.location = location;
            this.action = action;
            this.user = user;
            this.userUuid = userUuid;
            this.createdAt = createdAt;
        }
    }

    private final ConcurrentHashMap<Key, Entry> pending = new ConcurrentHashMap<>();
    private final long windowMs;
    private final Logger logger;

    ContainerDraftAggregator(long windowMs, Logger logger) {
        this.windowMs = Math.max(100L, windowMs);
        this.logger = logger;
    }

    boolean offer(Location location, ActionType action, Player player, List<ContainerDraft> drafts) {
        if (location == null || location.getWorld() == null || action == null
                || action.category() != ActionType.Category.CONTAINER
                || drafts == null || drafts.isEmpty()) {
            return false;
        }
        String user = player != null ? player.getName() : null;
        Key key = new Key(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), action, user);
        long now = System.currentTimeMillis();
        Location copy = location.clone();
        UUID userUuid = player != null ? player.getUniqueId() : null;
        pending.compute(key, (ignored, existing) -> {
            Entry entry = existing == null ? new Entry(copy, action, user, userUuid, now) : existing;
            for (ContainerDraft draft : drafts) {
                merge(entry.changes, draft);
            }
            return entry.changes.isEmpty() ? null : entry;
        });
        return true;
    }

    void flush(Consumer<ActivityRecord> sink, boolean all) {
        long now = System.currentTimeMillis();
        List<Entry> ready = new ArrayList<>();
        for (Key key : pending.keySet()) {
            // See ContainerAggregator: remove under the CHM key lock, encode after it is detached.
            pending.computeIfPresent(key, (ignored, value) -> {
                if (!all && now - value.createdAt < windowMs) {
                    return value;
                }
                ready.add(value);
                return null;
            });
        }
        for (Entry value : ready) {
            List<ContainerChange> changes = new ArrayList<>(value.changes.size());
            for (ContainerDraft draft : value.changes.values()) {
                try {
                    changes.add(new ContainerChange(draft.slot(), ItemCodec.encode(draft.before()),
                            ItemCodec.encode(draft.after()), draft.beforeAmount(), draft.afterAmount()));
                } catch (RuntimeException exception) {
                    logger.log(Level.WARNING, "Failed to encode one deferred container slot", exception);
                }
            }
            if (!changes.isEmpty()) {
                sink.accept(ActivityRecord.container(value.location, value.action, value.user,
                        value.userUuid, changes));
            }
        }
    }

    private static void merge(Map<Integer, ContainerDraft> changes, ContainerDraft draft) {
        if (draft == null) {
            return;
        }
        ContainerDraft existing = changes.get(draft.slot());
        if (existing == null) {
            changes.put(draft.slot(), draft);
            return;
        }
        ContainerDraft merged = new ContainerDraft(draft.slot(), existing.before(), draft.after(),
                existing.beforeAmount(), draft.afterAmount());
        if (same(merged.before(), merged.after())) {
            changes.remove(draft.slot());
        } else {
            changes.put(draft.slot(), merged);
        }
    }

    private static boolean same(ItemStack left, ItemStack right) {
        if (left == null) {
            return right == null;
        }
        return right != null && left.isSimilar(right) && left.getAmount() == right.getAmount();
    }
}
