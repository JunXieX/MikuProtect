package dev.junxiex.mikuprotect.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void burstThenRefill() throws Exception {
        RateLimiter limiter = new RateLimiter(60_000, 2);
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
        Thread.sleep(20L);
        assertTrue(limiter.allow("a"));
    }

    @Test
    void unlimitedWhenZero() {
        RateLimiter limiter = new RateLimiter(0, 1);
        for (int index = 0; index < 100; index++) {
            assertTrue(limiter.allow("a"));
        }
    }

    @Test
    void separateKeys() {
        RateLimiter limiter = new RateLimiter(1, 1);
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("b"));
        assertFalse(limiter.allow("a"));
    }
}
