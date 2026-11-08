package dev.junxiex.mikuprotect.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class WalServiceTest {

    @Test
    void roundTripsActivityAndContainerPayload() throws Exception {
        Path directory = Files.createTempDirectory("mikuprotect-wal-test");
        try {
            WalService wal = new WalService(directory, Logger.getLogger("WalServiceTest"), true);
            UUID world = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            ActivityRecord record = ActivityRecord.restored(1234L, world, "world", 10, 64, -3,
                    ActionType.BLOCK_PLACE, "Steve", user, "minecraft:stone", "minecraft:stone",
                    "minecraft:air", new byte[]{1, 2}, 1, null, null, null, null, new byte[]{3}, new byte[]{4},
                    List.of(new ContainerChange(2, new byte[]{5}, new byte[]{6}, 1, 2)));
            wal.append(List.of(record));

            List<ActivityRecord> read = wal.read();
            assertEquals(1, read.size());
            ActivityRecord restored = read.get(0);
            assertEquals(1234L, restored.time);
            assertEquals(world, restored.worldUuid);
            assertEquals("world", restored.worldName);
            assertEquals(10, restored.x);
            assertEquals(-3, restored.z);
            assertEquals(ActionType.BLOCK_PLACE, restored.action);
            assertEquals("Steve", restored.userName);
            assertArrayEquals(new byte[]{3}, restored.meta);
            assertEquals(1, restored.containers.size());
            assertEquals(2, restored.containers.get(0).slot());
            assertTrue(wal.enabled());
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
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
