package dev.junxiex.mikuprotect.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler facade that works on both Paper and Folia. Paper exposes the same region scheduler API,
 * so no runtime branch is needed for scheduling; {@link #folia()} is only for diagnostics.
 */
public final class Scheduler {

    private static Plugin plugin;
    private static Boolean folia;

    private Scheduler() {
    }

    public static void init(Plugin owner) {
        plugin = owner;
    }

    public static Plugin plugin() {
        return plugin;
    }

    public static boolean folia() {
        Boolean cached = folia;
        if (cached == null) {
            cached = Bukkit.getServer() != null
                    && io.papermc.paper.ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
            folia = cached;
        }
        return cached;
    }

    public static void global(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }

    public static void async(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduled -> task.run());
    }

    public static void asyncDelayed(Runnable task, long delayMillis) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, scheduled -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
    }

    public static void at(Location location, Runnable task) {
        if (location == null || location.getWorld() == null) {
            global(task);
            return;
        }
        Bukkit.getRegionScheduler().run(plugin, location, scheduled -> task.run());
    }

    public static void entity(Entity entity, Runnable task) {
        if (entity == null) {
            global(task);
            return;
        }
        entity.getScheduler().run(plugin, scheduled -> task.run(), () -> global(task));
    }

    public static void entityDelayed(Entity entity, Runnable task, long delayTicks) {
        if (entity == null) {
            global(task);
            return;
        }
        entity.getScheduler().runDelayed(plugin, scheduled -> task.run(), () -> global(task), delayTicks);
    }

    public static ScheduledTask timerGlobal(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> task.run(), delayTicks, periodTicks);
    }

    public static ScheduledTask timerAsync(Runnable task, long delayMillis, long periodMillis) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduled -> task.run(), delayMillis, periodMillis,
                TimeUnit.MILLISECONDS);
    }

    public static boolean owned(Location location) {
        return location != null && location.getWorld() != null && Bukkit.isOwnedByCurrentRegion(location);
    }
}
