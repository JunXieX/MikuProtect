package dev.junxiex.mikuprotect.web;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket per remote address. Used by the optional HTTP API; no scheduler involved.
 */
final class RateLimiter {

    private record Bucket(double tokens, long nanos) {
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int perMinute;
    private final int burst;

    RateLimiter(int perMinute, int burst) {
        this.perMinute = Math.max(0, perMinute);
        this.burst = Math.max(1, burst);
    }

    boolean allow(String key) {
        if (perMinute <= 0) {
            return true;
        }
        long now = System.nanoTime();
        double perNano = perMinute / 60_000_000_000.0D;
        boolean[] allowed = {false};
        buckets.compute(key == null ? "unknown" : key, (ignored, current) -> {
            double tokens = current == null ? burst : current.tokens();
            long last = current == null ? now : current.nanos();
            tokens = Math.min(burst, tokens + (now - last) * perNano);
            if (tokens < 1.0D) {
                return new Bucket(tokens, now);
            }
            allowed[0] = true;
            return new Bucket(tokens - 1.0D, now);
        });
        if (buckets.size() > 20_000) {
            buckets.clear();
        }
        return allowed[0];
    }
}
