package dev.junxiex.mikuprotect.config;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Recommended defaults by server capacity. Explicit values in config.yml are used only in manual
 * mode; auto and named profiles override them so an upgrade cannot silently keep undersized limits.
 */
public enum PerformanceProfile {

    SMALL("small", 20, 20_000, 500, 750L, 1_500L, 2, "256MB", 25_000, 100_000, 20L, 4),
    MEDIUM("medium", 100, 50_000, 1_000, 500L, 1_000L, 3, "512MB", 100_000, 200_000, 25L, 6),
    LARGE("large", Integer.MAX_VALUE, 100_000, 2_000, 250L, 500L, 4, "1GB", 250_000, 300_000, 25L, 10);

    public final String key;
    public final int maxPlayers;
    public final int queueCapacity;
    public final int batchSize;
    public final long flushIntervalMs;
    public final long containerAggregationWindowMs;
    public final int duckdbThreads;
    public final String duckdbMemoryLimit;
    public final int worldEditMaxBlocks;
    public final int rollbackMaxRows;
    public final long rollbackBudgetMs;
    public final int mariadbPoolSize;

    PerformanceProfile(String key, int maxPlayers, int queueCapacity, int batchSize, long flushIntervalMs,
            long containerAggregationWindowMs, int duckdbThreads, String duckdbMemoryLimit,
            int worldEditMaxBlocks, int rollbackMaxRows, long rollbackBudgetMs, int mariadbPoolSize) {
        this.key = key;
        this.maxPlayers = maxPlayers;
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.containerAggregationWindowMs = containerAggregationWindowMs;
        this.duckdbThreads = duckdbThreads;
        this.duckdbMemoryLimit = duckdbMemoryLimit;
        this.worldEditMaxBlocks = worldEditMaxBlocks;
        this.rollbackMaxRows = rollbackMaxRows;
        this.rollbackBudgetMs = rollbackBudgetMs;
        this.mariadbPoolSize = mariadbPoolSize;
    }

    public static PerformanceProfile byPlayers(int maxPlayers) {
        if (maxPlayers <= 0) {
            return MEDIUM;
        }
        if (maxPlayers <= SMALL.maxPlayers) {
            return SMALL;
        }
        if (maxPlayers <= MEDIUM.maxPlayers) {
            return MEDIUM;
        }
        return LARGE;
    }

    /**
     * @return selected profile, or null when the numeric config values should be used
     */
    public static PerformanceProfile resolve(String raw, int maxPlayers, Logger logger) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("manual")) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("auto")) {
            return byPlayers(maxPlayers);
        }
        for (PerformanceProfile profile : values()) {
            if (profile.key.equals(value)) {
                return profile;
            }
        }
        logger.warning("Unknown performance.profile: " + raw + "; falling back to auto");
        return byPlayers(maxPlayers);
    }
}
