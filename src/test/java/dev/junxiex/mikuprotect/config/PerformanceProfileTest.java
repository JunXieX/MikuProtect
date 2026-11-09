package dev.junxiex.mikuprotect.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class PerformanceProfileTest {

    @Test
    void resolvesAutoByMaxPlayers() {
        assertEquals(PerformanceProfile.SMALL, PerformanceProfile.byPlayers(20));
        assertEquals(PerformanceProfile.MEDIUM, PerformanceProfile.byPlayers(21));
        assertEquals(PerformanceProfile.MEDIUM, PerformanceProfile.byPlayers(100));
        assertEquals(PerformanceProfile.LARGE, PerformanceProfile.byPlayers(101));
        assertEquals(PerformanceProfile.MEDIUM, PerformanceProfile.byPlayers(0));
    }

    @Test
    void resolvesNamedAndManualModes() {
        Logger logger = Logger.getAnonymousLogger();
        assertEquals(PerformanceProfile.SMALL, PerformanceProfile.resolve("small", 500, logger));
        assertEquals(PerformanceProfile.LARGE, PerformanceProfile.resolve("LARGE", 10, logger));
        assertEquals(PerformanceProfile.LARGE, PerformanceProfile.resolve("auto", 500, logger));
        assertNull(PerformanceProfile.resolve("manual", 500, logger));
        assertNull(PerformanceProfile.resolve(null, 500, logger));
        assertEquals(PerformanceProfile.MEDIUM, PerformanceProfile.resolve("unknown", 0, logger));
    }
}
