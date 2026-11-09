package dev.junxiex.mikuprotect;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * Runtime permission registration. Nodes are removed again on disable so a reload cannot leave
 * stale entries behind.
 */
public final class Permissions {

    public static final String COMMAND = "mikuprotect.command";
    public static final String LOOKUP = "mikuprotect.lookup";
    public static final String ROLLBACK = "mikuprotect.rollback";
    public static final String RESTORE = "mikuprotect.restore";
    public static final String PREVIEW = "mikuprotect.preview";
    public static final String UNDO = "mikuprotect.undo";
    public static final String PURGE = "mikuprotect.purge";
    public static final String STATUS = "mikuprotect.status";
    public static final String RELOAD = "mikuprotect.reload";
    public static final String TELEPORT = "mikuprotect.teleport";
    public static final String ALERTS = "mikuprotect.alerts";
    public static final String INSPECT = "mikuprotect.inspect";

    private static final List<Permission> REGISTERED = new ArrayList<>();

    private Permissions() {
    }

    public static void register(Plugin plugin, String alertPermission) {
        add(plugin, COMMAND, "Use the /mp command", PermissionDefault.OP);
        add(plugin, LOOKUP, "Use lookup and near", PermissionDefault.OP);
        add(plugin, ROLLBACK, "Perform rollbacks", PermissionDefault.OP);
        add(plugin, RESTORE, "Perform restores", PermissionDefault.OP);
        add(plugin, PREVIEW, "Preview and apply rollbacks", PermissionDefault.OP);
        add(plugin, UNDO, "Undo the last modification", PermissionDefault.OP);
        add(plugin, PURGE, "Purge old records", PermissionDefault.OP);
        add(plugin, STATUS, "View plugin status", PermissionDefault.OP);
        add(plugin, RELOAD, "Reload the configuration", PermissionDefault.OP);
        add(plugin, TELEPORT, "Teleport to logged positions", PermissionDefault.OP);
        add(plugin, ALERTS, "Receive ore alerts", PermissionDefault.OP);
        if (alertPermission != null && !alertPermission.isBlank() && !alertPermission.equals(ALERTS)) {
            add(plugin, alertPermission, "Receive ore alerts", PermissionDefault.OP);
        }
        add(plugin, INSPECT, "Use the inspector", PermissionDefault.OP);
    }

    private static void add(Plugin plugin, String name, String description, PermissionDefault defaultValue) {
        if (plugin.getServer().getPluginManager().getPermission(name) != null) {
            return;
        }
        Permission permission = new Permission(name, description, defaultValue);
        plugin.getServer().getPluginManager().addPermission(permission);
        REGISTERED.add(permission);
    }

    public static void unregister(Plugin plugin) {
        for (Permission permission : REGISTERED) {
            plugin.getServer().getPluginManager().removePermission(permission);
        }
        REGISTERED.clear();
    }
}
