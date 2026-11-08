package dev.junxiex.mikuprotect.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RollbackResultTest {

    @Test
    void mergeKeepsCountsAndChanges() {
        RollbackResult left = new RollbackResult();
        left.applied = 2;
        left.skipped = 1;
        left.failed = 1;
        left.errors.add("a");
        left.changes.add(change(1));
        RollbackResult right = new RollbackResult();
        right.applied = 3;
        right.skipped = 2;
        right.completed = true;
        right.errors.add("b");
        right.changes.add(change(2));
        left.merge(right);
        assertEquals(5, left.applied);
        assertEquals(3, left.skipped);
        assertEquals(1, left.failed);
        assertEquals(List.of("a", "b"), left.errors);
        assertEquals(2, left.changes.size());
        assertEquals(9, left.total());
        assertFalse(left.completed);
    }

    private static AppliedChange change(long id) {
        return new AppliedChange(id, UUID.randomUUID(), 0, 0, 0, null, null, null, null,
                null, null, -1, null, null, null, null, false);
    }
}
