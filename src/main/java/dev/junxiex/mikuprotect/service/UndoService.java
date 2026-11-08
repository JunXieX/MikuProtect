package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.model.AppliedChange;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the last applied change set per actor so /mp undo can reverse it without re-querying.
 */
public final class UndoService {

    private final Map<UUID, List<AppliedChange>> lastChanges = new ConcurrentHashMap<>();

    public void remember(UUID actor, List<AppliedChange> changes) {
        if (actor == null || changes == null || changes.isEmpty()) {
            return;
        }
        lastChanges.put(actor, List.copyOf(changes));
    }

    public List<AppliedChange> peek(UUID actor) {
        return lastChanges.get(actor);
    }

    public List<AppliedChange> take(UUID actor) {
        return lastChanges.remove(actor);
    }

    public void clear(UUID actor) {
        lastChanges.remove(actor);
    }
}
