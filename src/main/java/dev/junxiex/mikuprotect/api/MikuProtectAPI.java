package dev.junxiex.mikuprotect.api;

import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.RollbackResult;
import dev.junxiex.mikuprotect.model.StoredActivity;
import dev.junxiex.mikuprotect.storage.StorageType;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Public API, registered in the Bukkit services manager.
 */
public interface MikuProtectAPI {

    StorageType storageType();

    CompletableFuture<List<StoredActivity>> lookup(LookupQuery query);

    CompletableFuture<Long> count(LookupQuery query);

    CompletableFuture<RollbackResult> rollback(LookupQuery query, Object owner);

    CompletableFuture<RollbackResult> restore(LookupQuery query, Object owner);

    CompletableFuture<RollbackResult> preview(LookupQuery query, Object owner);

    boolean record(ActivityRecord record);
}
