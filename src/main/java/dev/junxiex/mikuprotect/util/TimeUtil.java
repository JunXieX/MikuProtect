package dev.junxiex.mikuprotect.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class TimeUtil {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    /**
     * Parses durations such as {@code 30s}, {@code 15m}, {@code 12h}, {@code 7d}, {@code 2w},
     * {@code 3mo}, {@code 1y}. A bare number is interpreted as minutes.
     */
    public static long parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty duration");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        long multiplier;
        String number = value;
        if (value.endsWith("mo")) {
            multiplier = TimeUnit.DAYS.toMillis(30);
            number = value.substring(0, value.length() - 2);
        } else if (value.endsWith("y")) {
            multiplier = TimeUnit.DAYS.toMillis(365);
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("w")) {
            multiplier = TimeUnit.DAYS.toMillis(7);
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("d")) {
            multiplier = TimeUnit.DAYS.toMillis(1);
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("h")) {
            multiplier = TimeUnit.HOURS.toMillis(1);
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = TimeUnit.MINUTES.toMillis(1);
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("s")) {
            multiplier = TimeUnit.SECONDS.toMillis(1);
            number = value.substring(0, value.length() - 1);
        } else {
            multiplier = TimeUnit.MINUTES.toMillis(1);
        }
        long parsed = Long.parseLong(number);
        return Math.multiplyExact(parsed, multiplier);
    }

    public static String format(long epochMillis) {
        return FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String ago(long epochMillis) {
        long diff = System.currentTimeMillis() - epochMillis;
        if (diff < 0) {
            diff = 0;
        }
        long seconds = diff / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }
}
