package dev.junxiex.mikuprotect.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TimeUtilTest {

    @Test
    void parsesCommonUnits() {
        assertEquals(30_000L, TimeUtil.parseDuration("30s"));
        assertEquals(15 * 60_000L, TimeUtil.parseDuration("15m"));
        assertEquals(12 * 3_600_000L, TimeUtil.parseDuration("12h"));
        assertEquals(7 * 86_400_000L, TimeUtil.parseDuration("7d"));
        assertEquals(2 * 7 * 86_400_000L, TimeUtil.parseDuration("2w"));
        assertEquals(30L * 86_400_000L, TimeUtil.parseDuration("1mo"));
        assertEquals(365L * 86_400_000L, TimeUtil.parseDuration("1y"));
    }

    @Test
    void bareNumberMeansMinutes() {
        assertEquals(5 * 60_000L, TimeUtil.parseDuration("5"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(RuntimeException.class, () -> TimeUtil.parseDuration("abc"));
    }
}
