package dev.junxiex.benchprobe;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs a fixed TNT workload and records both measured task intervals and the server's own tick
 * metrics. The probe is intentionally tiny so the comparison reflects the logging plugins.
 */
public final class BenchProbe extends JavaPlugin {

    private boolean running;

    @Override
    public void onEnable() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(Commands.literal("benchprobe")
                        .then(Commands.argument("tnt", IntegerArgumentType.integer(1, 5000))
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 1200))
                                        .executes(context -> {
                                            start(context.getSource().getSender(),
                                                    IntegerArgumentType.getInteger(context, "tnt"),
                                                    IntegerArgumentType.getInteger(context, "ticks"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .build(), "Run deterministic TNT benchmark", List.of()));
        getLogger().info("BENCH_PROBE_READY");
    }

    private void start(CommandSender sender, int tntCount, int ticks) {
        if (running) {
            sender.sendMessage("benchprobe is already running");
            return;
        }
        World world = Bukkit.getWorlds().get(0);
        if (world == null) {
            sender.sendMessage("no world loaded");
            return;
        }
        prepare(world);
        running = true;
        RunState state = new RunState(tntCount, ticks);
        getLogger().info("[BENCH_START] tnt=" + tntCount + " ticks=" + ticks);
        state.task = Bukkit.getScheduler().runTaskTimer(this, () -> tick(world, state), 1L, 1L);
        sender.sendMessage("benchprobe started: tnt=" + tntCount + " ticks=" + ticks);
    }

    private void prepare(World world) {
        for (int cx = -1; cx <= 4; cx++) {
            for (int cz = -1; cz <= 3; cz++) {
                world.setChunkForceLoaded(cx, cz, true);
                world.getChunkAt(cx, cz, true);
            }
        }
        world.getEntitiesByClass(TNTPrimed.class).forEach(TNTPrimed::remove);
        // Three solid layers keep the TNT explosion inside a deterministic block budget.
        for (int x = -8; x < 64; x++) {
            for (int z = -8; z < 40; z++) {
                for (int y = -63; y <= -61; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE, false);
                }
            }
        }
        for (int i = 0; i < 200; i++) {
            int x = 40 + (i % 20);
            int z = -8 + (i / 20);
            world.getBlockAt(x, -61, z).setType(Material.CHEST, false);
            world.getBlockAt(x, -60, z).setType(Material.HOPPER, false);
            if (world.getBlockAt(x, -60, z).getState() instanceof Container container) {
                container.getInventory().setItem(0, new ItemStack(Material.DIRT, 64));
                container.update(true, false);
            }
        }
    }

    private static void spawnZombies(World world) {
        for (int i = 0; i < 200; i++) {
            int x = -8 + (i % 20);
            int z = 20 + (i / 20);
            Zombie zombie = world.spawn(new Location(world, x + 0.5, -60.0, z + 0.5), Zombie.class);
            zombie.setAI(false);
            zombie.setShouldBurnInDay(false);
        }
    }

    private static void killZombies(World world) {
        for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
            zombie.damage(1000.0);
        }
    }

    private void tick(World world, RunState state) {
        state.elapsedTicks++;
        if (state.elapsedTicks <= RunState.WARMUP_TICKS) {
            return;
        }
        if (state.measuredTicks == 0) {
            // Let platform lighting/tile updates settle before the measured burst.
            spawnTnt(world, state.tntCount);
            spawnZombies(world);
            state.lastTickNanos = System.nanoTime();
        }
        if (state.measuredTicks == state.durationTicks / 2) {
            killZombies(world);
        }
        long now = System.nanoTime();
        if (state.lastTickNanos != 0L) {
            state.intervalsMs.add((now - state.lastTickNanos) / 1_000_000.0);
        }
        state.lastTickNanos = now;

        double serverTick = Bukkit.getServer().getAverageTickTime();
        state.serverTickSum += serverTick;
        state.serverTickMax = Math.max(state.serverTickMax, serverTick);
        double tps = Bukkit.getServer().getTPS()[0];
        state.tpsSum += tps;
        state.tpsMin = Math.min(state.tpsMin, tps);
        state.measuredTicks++;

        if (state.measuredTicks >= state.durationTicks) {
            finish(state);
        }
    }

    private static void spawnTnt(World world, int count) {
        for (int i = 0; i < count; i++) {
            int x = i % 32;
            int z = (i / 32) % 32;
            Location location = new Location(world, x + 0.5, -60.0, z + 0.5);
            TNTPrimed tnt = world.spawn(location, TNTPrimed.class);
            tnt.setFuseTicks(0);
        }
    }

    private void finish(RunState state) {
        state.task.cancel();
        running = false;
        List<Double> intervals = new ArrayList<>(state.intervalsMs);
        Collections.sort(intervals);
        double intervalAvg = average(intervals);
        double p50 = percentile(intervals, 0.50);
        double p95 = percentile(intervals, 0.95);
        double intervalMax = intervals.isEmpty() ? 0.0 : intervals.get(intervals.size() - 1);
        double serverAvg = state.measuredTicks == 0 ? 0.0 : state.serverTickSum / state.measuredTicks;
        double tpsAvg = state.measuredTicks == 0 ? 0.0 : state.tpsSum / state.measuredTicks;
        int entities = Bukkit.getWorlds().get(0).getEntitiesByClass(TNTPrimed.class).size();
        getLogger().info("[BENCH_RESULT] tnt=" + state.tntCount
                + " ticks=" + state.durationTicks
                + " samples=" + intervals.size()
                + " interval_avg_ms=" + format(intervalAvg)
                + " interval_p50_ms=" + format(p50)
                + " interval_p95_ms=" + format(p95)
                + " interval_max_ms=" + format(intervalMax)
                + " server_tick_avg_ms=" + format(serverAvg)
                + " server_tick_max_ms=" + format(state.serverTickMax)
                + " tps_avg=" + format(tpsAvg)
                + " tps_min=" + format(state.tpsMin)
                + " tnt_left=" + entities
                + " hoppers=200 zombies=200");
        getLogger().info("BENCH_DONE");
    }

    private static double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static final class RunState {
        private static final int WARMUP_TICKS = 40;
        private final int tntCount;
        private final int durationTicks;
        private final List<Double> intervalsMs = new ArrayList<>();
        private int elapsedTicks;
        private int measuredTicks;
        private long lastTickNanos;
        private double serverTickSum;
        private double serverTickMax;
        private double tpsSum;
        private double tpsMin = Double.MAX_VALUE;
        private BukkitTask task;

        private RunState(int tntCount, int durationTicks) {
            this.tntCount = tntCount;
            this.durationTicks = durationTicks;
        }
    }
}
