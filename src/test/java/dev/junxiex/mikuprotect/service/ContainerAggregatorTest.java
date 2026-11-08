package dev.junxiex.mikuprotect.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerAggregatorTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private static ActivityRecord record(int x, int slot, byte before, byte after, int beforeAmount, int afterAmount) {
        return ActivityRecord.restored(System.currentTimeMillis(), WORLD, "world", x, 64, 0,
                ActionType.CONTAINER_ADD, "Steve", USER, null, null, null, null, 0, null, null, null, null,
                null, null, List.of(new ContainerChange(slot, new byte[]{before}, new byte[]{after},
                beforeAmount, afterAmount)));
    }

    @Test
    void mergesFirstBeforeWithLastAfter() {
        ContainerAggregator aggregator = new ContainerAggregator(60_000L);
        assertTrue(aggregator.offer(record(1, 0, (byte) 1, (byte) 2, 1, 2)));
        assertTrue(aggregator.offer(record(1, 0, (byte) 2, (byte) 3, 2, 3)));
        List<ActivityRecord> out = new ArrayList<>();
        aggregator.flush(out::add, true);
        assertEquals(1, out.size());
        ContainerChange change = out.get(0).containers.get(0);
        assertArrayEquals(new byte[]{1}, change.beforeData());
        assertArrayEquals(new byte[]{3}, change.afterData());
        assertEquals(1, change.beforeAmount());
        assertEquals(3, change.afterAmount());
    }

    @Test
    void dropsNetZeroChange() {
        ContainerAggregator aggregator = new ContainerAggregator(60_000L);
        aggregator.offer(record(1, 0, (byte) 1, (byte) 2, 1, 2));
        aggregator.offer(record(1, 0, (byte) 2, (byte) 1, 2, 1));
        List<ActivityRecord> out = new ArrayList<>();
        aggregator.flush(out::add, true);
        assertTrue(out.isEmpty());
    }

    @Test
    void keepsDifferentLocationsSeparate() {
        ContainerAggregator aggregator = new ContainerAggregator(60_000L);
        aggregator.offer(record(1, 0, (byte) 1, (byte) 2, 1, 2));
        aggregator.offer(record(2, 0, (byte) 1, (byte) 2, 1, 2));
        List<ActivityRecord> out = new ArrayList<>();
        aggregator.flush(out::add, true);
        assertEquals(2, out.size());
    }
}
