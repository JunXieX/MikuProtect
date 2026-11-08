package dev.junxiex.mikuprotect.api.event;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before an activity enters the recording queue. Cancel to drop the record.
 *
 * <p>On Paper the event is only fired on the primary thread; asynchronous chat records skip it.
 * On Folia it is fired on the owning region thread.</p>
 */
public final class MikuProtectPreLogEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ActivityRecord record;
    private boolean cancelled;

    public MikuProtectPreLogEvent(ActivityRecord record) {
        this.record = record;
    }

    public ActivityRecord record() {
        return record;
    }

    public ActionType action() {
        return record == null ? null : record.action;
    }

    public String user() {
        return record == null ? null : record.userName;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
