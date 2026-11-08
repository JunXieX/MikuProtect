package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.StoredActivity;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.util.Scheduler;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class LookupService {

    private final MikuProtectPlugin plugin;
    private final Storage storage;

    public LookupService(MikuProtectPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public CompletableFuture<List<StoredActivity>> lookup(LookupQuery query) {
        CompletableFuture<List<StoredActivity>> future = new CompletableFuture<>();
        try {
            Scheduler.async(() -> {
                try {
                    future.complete(storage.query(query));
                } catch (Exception exception) {
                    plugin.getLogger().warning("Lookup failed: " + exception.getMessage());
                    future.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    public CompletableFuture<Long> count(LookupQuery query) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        try {
            Scheduler.async(() -> {
                try {
                    future.complete(storage.count(query));
                } catch (Exception exception) {
                    future.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }
}
