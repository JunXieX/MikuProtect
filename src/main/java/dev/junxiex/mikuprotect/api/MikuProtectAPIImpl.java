package dev.junxiex.mikuprotect.api;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.RollbackRequest;
import dev.junxiex.mikuprotect.model.RollbackResult;
import dev.junxiex.mikuprotect.model.StoredActivity;
import dev.junxiex.mikuprotect.storage.StorageType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;

public final class MikuProtectAPIImpl implements MikuProtectAPI {

    private final MikuProtectPlugin plugin;

    public MikuProtectAPIImpl(MikuProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public StorageType storageType() {
        return plugin.storage().type();
    }

    @Override
    public CompletableFuture<List<StoredActivity>> lookup(LookupQuery query) {
        return plugin.lookup().lookup(query);
    }

    @Override
    public CompletableFuture<Long> count(LookupQuery query) {
        return plugin.lookup().count(query);
    }

    @Override
    public CompletableFuture<RollbackResult> rollback(LookupQuery query, Object owner) {
        return plugin.rollback().apply(new RollbackRequest(RollbackRequest.Mode.ROLLBACK, query.copy(),
                owner instanceof CommandSender sender ? sender : null,
                plugin.config().rollbackMaxRows, plugin.config().applyPhysics));
    }

    @Override
    public CompletableFuture<RollbackResult> restore(LookupQuery query, Object owner) {
        return plugin.rollback().apply(new RollbackRequest(RollbackRequest.Mode.RESTORE, query.copy(),
                owner instanceof CommandSender sender ? sender : null,
                plugin.config().rollbackMaxRows, plugin.config().applyPhysics));
    }

    @Override
    public CompletableFuture<RollbackResult> preview(LookupQuery query, Object owner) {
        return plugin.rollback().apply(new RollbackRequest(RollbackRequest.Mode.PREVIEW, query.copy(),
                owner instanceof CommandSender sender ? sender : null,
                plugin.config().rollbackMaxRows, plugin.config().applyPhysics));
    }

    @Override
    public boolean record(ActivityRecord record) {
        return plugin.recording().record(record);
    }
}
