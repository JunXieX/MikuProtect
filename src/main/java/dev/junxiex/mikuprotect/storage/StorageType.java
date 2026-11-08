package dev.junxiex.mikuprotect.storage;

import java.util.Locale;

public enum StorageType {
    DUCKDB,
    MARIADB;

    public static StorageType parse(String value) {
        if (value == null || value.isBlank()) {
            return DUCKDB;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "duckdb" -> DUCKDB;
            case "mariadb", "mysql" -> MARIADB;
            default -> throw new IllegalArgumentException("Unsupported storage type: " + value);
        };
    }
}
