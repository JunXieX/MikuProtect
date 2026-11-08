package dev.junxiex.mikuprotect.config;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.storage.StorageType;
import dev.junxiex.mikuprotect.util.TimeUtil;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable configuration snapshot. Replaced wholesale on reload.
 */
public final class MikuConfig {

    public final StorageType storageType;
    public final String duckdbFile;
    public final String duckdbMemoryLimit;
    public final int duckdbThreads;
    public final String duckdbMaxTempSize;

    public final String mariadbHost;
    public final int mariadbPort;
    public final String mariadbDatabase;
    public final String mariadbUsername;
    public final String mariadbPassword;
    public final int mariadbPoolSize;

    public final int queueCapacity;
    public final int batchSize;
    public final long flushIntervalMs;
    public final boolean aggregateContainers;
    public final long containerAggregationWindowMs;
    public final boolean walEnabled;
    public final boolean ignoreCreative;
    public final boolean worldEditEnabled;
    public final int worldEditMaxBlocks;
    public final Set<String> worlds;
    public final Set<ActionType> disabledActions;
    public final int maxMessageLength;

    public final int defaultRadius;
    public final int maxRadius;
    public final long defaultSince;
    public final int pageSize;
    public final int maxPageSize;

    public final int rollbackMaxRows;
    public final long rollbackBudgetMs;
    public final long rollbackMinBudgetMs;
    public final long rollbackMaxBudgetMs;
    public final boolean applyPhysics;
    public final boolean redstoneRefresh;

    public final boolean purgeAuto;
    public final int purgeDays;
    public final int purgeBatch;

    public final boolean webEnabled;
    public final String webBind;
    public final int webPort;
    public final String webApiKey;
    public final int webMaxResults;
    public final int webRateLimitPerMinute;
    public final int webRateLimitBurst;

    public final boolean alertsEnabled;
    public final Set<Material> alertBlocks;
    public final String alertPermission;

    public final boolean debug;

    public MikuConfig(FileConfiguration config, Logger logger) {
        this.storageType = StorageType.parse(config.getString("storage.type", "duckdb"));
        this.duckdbFile = config.getString("storage.duckdb.file", "data/mikuprotect.duckdb");
        this.duckdbMemoryLimit = config.getString("storage.duckdb.memory-limit", "512MB");
        this.duckdbThreads = Math.max(1, config.getInt("storage.duckdb.threads", 3));
        this.duckdbMaxTempSize = config.getString("storage.duckdb.max-temp-size", "10GB");

        this.mariadbHost = config.getString("storage.mariadb.host", "127.0.0.1");
        this.mariadbPort = Math.min(65535, Math.max(1, config.getInt("storage.mariadb.port", 3306)));
        this.mariadbDatabase = config.getString("storage.mariadb.database", "mikuprotect");
        this.mariadbUsername = config.getString("storage.mariadb.username", "root");
        this.mariadbPassword = config.getString("storage.mariadb.password", "");
        this.mariadbPoolSize = Math.max(2, config.getInt("storage.mariadb.pool-size", 6));

        this.queueCapacity = Math.max(512, config.getInt("recording.queue-capacity", 50000));
        this.batchSize = Math.max(1, config.getInt("recording.batch-size", 1000));
        this.flushIntervalMs = Math.max(50L, config.getLong("recording.flush-interval-ms", 500L));
        this.aggregateContainers = config.getBoolean("recording.aggregate-containers", true);
        this.walEnabled = config.getBoolean("recording.wal-enabled", true);
        this.containerAggregationWindowMs =
                Math.max(100L, config.getLong("recording.container-aggregation-window-ms", 1000L));
        this.ignoreCreative = config.getBoolean("recording.ignore-creative", false);
        this.worldEditEnabled = config.getBoolean("recording.log-worldedit", true);
        this.worldEditMaxBlocks = Math.max(0, config.getInt("recording.worldedit-max-blocks", 100000));
        this.worlds = Collections.unmodifiableSet(new HashSet<>(config.getStringList("recording.worlds")));
        this.maxMessageLength = Math.max(32, config.getInt("recording.max-message-length", 256));

        EnumSet<ActionType> disabled = EnumSet.noneOf(ActionType.class);
        for (String key : config.getStringList("recording.disabled-actions")) {
            ActionType type = ActionType.byKey(key);
            if (type == null) {
                logger.warning("Unknown action in recording.disabled-actions: " + key);
            } else {
                disabled.add(type);
            }
        }
        addDisabled(config, disabled, "log-block-place", ActionType.BLOCK_PLACE);
        addDisabled(config, disabled, "log-block-break", ActionType.BLOCK_BREAK);
        addDisabled(config, disabled, "log-block-burn", ActionType.BLOCK_BURN);
        addDisabled(config, disabled, "log-block-fade", ActionType.BLOCK_FADE);
        addDisabled(config, disabled, "log-block-form", ActionType.BLOCK_FORM);
        addDisabled(config, disabled, "log-block-spread", ActionType.BLOCK_SPREAD);
        addDisabled(config, disabled, "log-block-ignite", ActionType.BLOCK_IGNITE);
        addDisabled(config, disabled, "log-pistons", ActionType.BLOCK_PISTON_EXTEND, ActionType.BLOCK_PISTON_RETRACT);
        addDisabled(config, disabled, "log-block-movement", ActionType.BLOCK_PISTON_EXTEND, ActionType.BLOCK_PISTON_RETRACT);
        addDisabled(config, disabled, "log-dispensers", ActionType.BLOCK_DISPENSE);
        addDisabled(config, disabled, "log-explosions", ActionType.BLOCK_EXPLOSION);
        addDisabled(config, disabled, "log-entity-change", ActionType.BLOCK_ENTITY_CHANGE);
        addDisabled(config, disabled, "log-buckets", ActionType.BLOCK_BUCKET_EMPTY, ActionType.BLOCK_BUCKET_FILL);
        addDisabled(config, disabled, "log-leaf-decay", ActionType.BLOCK_DECAY);
        addDisabled(config, disabled, "log-tree-growth", ActionType.BLOCK_GROWTH);
        addDisabled(config, disabled, "log-mushroom-growth", ActionType.BLOCK_GROWTH);
        addDisabled(config, disabled, "log-vine-growth", ActionType.BLOCK_GROWTH);
        addDisabled(config, disabled, "log-sculk-spread", ActionType.BLOCK_SPREAD);
        addDisabled(config, disabled, "log-water-flow", ActionType.BLOCK_LIQUID_FLOW);
        addDisabled(config, disabled, "log-lava-flow", ActionType.BLOCK_LIQUID_FLOW);
        addDisabled(config, disabled, "log-liquid-tracking", ActionType.BLOCK_LIQUID_FLOW);
        addDisabled(config, disabled, "log-fertilize", ActionType.BLOCK_FERTILIZE);
        addDisabled(config, disabled, "log-structure-growth", ActionType.WORLD_STRUCTURE);
        addDisabled(config, disabled, "log-container-transactions", ActionType.CONTAINER_ADD, ActionType.CONTAINER_REMOVE);
        addDisabled(config, disabled, "log-hopper-transactions", ActionType.CONTAINER_ADD, ActionType.CONTAINER_REMOVE);
        addDisabled(config, disabled, "log-item-transactions", ActionType.ITEM_DROP, ActionType.ITEM_PICKUP,
                ActionType.ITEM_CRAFT, ActionType.ITEM_BREAK, ActionType.ITEM_CONSUME);
        addDisabled(config, disabled, "log-item-drops", ActionType.ITEM_DROP);
        addDisabled(config, disabled, "log-item-pickups", ActionType.ITEM_PICKUP);
        addDisabled(config, disabled, "log-entity-spawns", ActionType.ENTITY_SPAWN);
        addDisabled(config, disabled, "log-entity-kills", ActionType.ENTITY_KILL);
        addDisabled(config, disabled, "log-entity-explosions", ActionType.ENTITY_EXPLODE);
        addDisabled(config, disabled, "log-player-kills", ActionType.PLAYER_KILL);
        addDisabled(config, disabled, "log-player-deaths", ActionType.PLAYER_DEATH);
        addDisabled(config, disabled, "log-player-interactions", ActionType.PLAYER_INTERACT,
                ActionType.CONTAINER_OPEN);
        addDisabled(config, disabled, "log-player-messages", ActionType.PLAYER_CHAT);
        addDisabled(config, disabled, "log-player-commands", ActionType.PLAYER_COMMAND);
        addDisabled(config, disabled, "log-player-sessions", ActionType.PLAYER_JOIN, ActionType.PLAYER_QUIT);
        addDisabled(config, disabled, "log-sign-text", ActionType.SIGN_CHANGE);
        addDisabled(config, disabled, "log-portals", ActionType.WORLD_PORTAL);
        addDisabled(config, disabled, "log-username-changes", ActionType.PLAYER_NAME_CHANGE);
        this.disabledActions = Collections.unmodifiableSet(disabled);

        this.defaultRadius = Math.max(0, config.getInt("lookup.default-radius", 10));
        this.maxRadius = Math.max(0, config.getInt("lookup.max-radius", 100));
        long since;
        try {
            since = TimeUtil.parseDuration(config.getString("lookup.default-since", "3d"));
        } catch (RuntimeException exception) {
            logger.warning("Invalid lookup.default-since, falling back to 3d");
            since = TimeUtil.parseDuration("3d");
        }
        this.defaultSince = since;
        this.pageSize = Math.max(1, config.getInt("lookup.page-size", 10));
        this.maxPageSize = Math.max(this.pageSize, config.getInt("lookup.max-page-size", 100));

        this.rollbackMaxRows = Math.max(1000, config.getInt("rollback.max-rows", 200000));
        this.rollbackBudgetMs = Math.max(1L, config.getLong("rollback.budget-ms", 25L));
        this.rollbackMinBudgetMs = Math.max(1L, config.getLong("rollback.min-budget-ms", 10L));
        this.rollbackMaxBudgetMs = Math.max(this.rollbackMinBudgetMs, config.getLong("rollback.max-budget-ms", 50L));
        this.applyPhysics = config.getBoolean("rollback.apply-physics", false);
        this.redstoneRefresh = config.getBoolean("rollback.redstone-refresh", false);

        this.purgeAuto = config.getBoolean("purge.auto-enabled", false);
        this.purgeDays = Math.max(1, config.getInt("purge.auto-days", 30));
        this.purgeBatch = Math.max(100, config.getInt("purge.batch-size", 5000));

        this.webEnabled = config.getBoolean("web.enabled", false);
        this.webBind = config.getString("web.bind", "127.0.0.1");
        this.webPort = Math.min(65535, Math.max(1, config.getInt("web.port", 4040)));
        this.webApiKey = config.getString("web.api-key", "");
        this.webMaxResults = Math.max(1, config.getInt("web.max-results", 100));
        this.webRateLimitPerMinute = Math.max(0, config.getInt("web.rate-limit-per-minute", 120));
        this.webRateLimitBurst = Math.max(1, config.getInt("web.rate-limit-burst", 30));

        this.alertsEnabled = config.getBoolean("alerts.enabled", true);
        Set<Material> blocks = EnumSet.noneOf(Material.class);
        for (String name : config.getStringList("alerts.block-break")) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                logger.warning("Unknown material in alerts.block-break: " + name);
            } else {
                blocks.add(material);
            }
        }
        this.alertBlocks = Collections.unmodifiableSet(blocks);
        this.alertPermission = config.getString("alerts.permission", "mikuprotect.alerts");

        this.debug = config.getBoolean("debug", false);
    }

    private static void addDisabled(FileConfiguration config, Set<ActionType> disabled, String path,
            ActionType... types) {
        if (!config.getBoolean("recording." + path, true)) {
            Collections.addAll(disabled, types);
        }
    }
}
