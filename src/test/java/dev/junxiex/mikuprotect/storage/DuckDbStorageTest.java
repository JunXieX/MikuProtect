package dev.junxiex.mikuprotect.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.StoredActivity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DuckDbStorageTest {

    @Test
    void writesQueriesMarksAndPurges() throws Exception {
        Path directory = Files.createTempDirectory("mikuprotect-duckdb-test");
        DuckDbStorage storage = null;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("storage.type", "duckdb");
            yaml.set("storage.duckdb.file", directory.resolve("test.duckdb").toString());
            yaml.set("storage.duckdb.threads", 2);
            MikuConfig config = new MikuConfig(yaml, Logger.getLogger("DuckDbStorageTest"));
            storage = new DuckDbStorage(config, directory, Logger.getLogger("DuckDbStorageTest"));
            storage.init();

            UUID world = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            ActivityRecord record = ActivityRecord.restored(System.currentTimeMillis(), world, "world", 10, 64,
                    -5, ActionType.BLOCK_PLACE, "Steve", user, "minecraft:stone", "minecraft:stone",
                    "minecraft:air", null, 1, null, null, null, null, new byte[]{1, 2}, new byte[]{3, 4},
                    List.of(new ContainerChange(4, new byte[]{5}, new byte[]{6}, 1, 2)));
            storage.write(List.of(record));

            List<StoredActivity> rows = storage.query(
                    LookupQuery.all().world("world").user("steve").limit(10));
            assertEquals(1, rows.size());
            StoredActivity row = rows.get(0);
            assertEquals(ActionType.BLOCK_PLACE, row.action());
            assertEquals("Steve", row.userName());
            assertArrayEquals(new byte[]{1, 2}, row.meta());
            assertArrayEquals(new byte[]{3, 4}, row.oldMeta());
            assertEquals(1, row.containers().size());
            assertEquals(4, row.containers().get(0).slot());

            storage.markRolledBack(List.of(row.id()), true);
            assertTrue(storage.query(LookupQuery.all().limit(10)).get(0).rolledBack());
            assertEquals("Steve", storage.userName(user));

            assertEquals(1, storage.purge(System.currentTimeMillis() + 1000L, null, null));
            assertEquals(0, storage.count(LookupQuery.all()));
        } finally {
            if (storage != null) {
                storage.close();
            }
            try (var files = Files.walk(directory)) {
                files.sorted((left, right) -> right.getNameCount() - left.getNameCount()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // best effort
                    }
                });
            }
        }
    }
}
