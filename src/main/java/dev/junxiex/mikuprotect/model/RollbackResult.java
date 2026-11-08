package dev.junxiex.mikuprotect.model;

import java.util.ArrayList;
import java.util.List;

public final class RollbackResult {

    public int applied;
    public int skipped;
    public int failed;
    public boolean completed;
    public final List<String> errors = new ArrayList<>();
    public final List<AppliedChange> changes = new ArrayList<>();

    public int total() {
        return applied + skipped + failed;
    }

    public void merge(RollbackResult other) {
        applied += other.applied;
        skipped += other.skipped;
        failed += other.failed;
        for (String error : other.errors) {
            if (errors.size() >= 20) {
                break;
            }
            errors.add(error);
        }
        changes.addAll(other.changes);
        completed = completed && other.completed;
    }
}
