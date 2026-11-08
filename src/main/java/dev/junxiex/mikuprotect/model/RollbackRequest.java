package dev.junxiex.mikuprotect.model;

import org.bukkit.command.CommandSender;

public final class RollbackRequest {

    public enum Mode {
        ROLLBACK,
        RESTORE,
        PREVIEW
    }

    public final Mode mode;
    public final LookupQuery query;
    public final CommandSender sender;
    public final int maxRows;
    public final boolean applyPhysics;

    public RollbackRequest(Mode mode, LookupQuery query, CommandSender sender, int maxRows, boolean applyPhysics) {
        this.mode = mode;
        this.query = query;
        this.sender = sender;
        this.maxRows = maxRows;
        this.applyPhysics = applyPhysics;
    }
}
