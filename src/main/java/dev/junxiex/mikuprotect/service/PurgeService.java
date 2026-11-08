package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.util.Scheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PurgeService {

    private final MikuProtectPlugin plugin;
    private final Storage storage;
    private final AtomicBoolean running = new AtomicBoolean();

    public PurgeService(MikuProtectPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public boolean running() {
        return running.get();
    }

    public CompletableFuture<Integer> purge(long before, String world, String user) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        if (!running.compareAndSet(false, true)) {
            future.completeExceptionally(new IllegalStateException("A purge is already running"));
            return future;
        }
        try {
            Scheduler.async(() -> {
                try {
                    int removed = storage.purge(before, world, user);
                    future.complete(removed);
                } catch (Exception exception) {
                    plugin.getLogger().warning("Purge failed: " + exception.getMessage());
                    future.completeExceptionally(exception);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            future.completeExceptionally(exception);
        }
        return future;
    }
}
