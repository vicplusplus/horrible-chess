package com.horriblechess.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter keyed by an arbitrary string (e.g. client IP).
 * Cheap and in-memory — enough to blunt create-game spam without standing up
 * real infrastructure. Each key tracks the current window's start and count.
 */
@Component
public class RateLimiter {

    private final int maxPerWindow;
    private final long windowMillis;
    private final Clock clock;
    // key -> [windowStartMillis, countInWindow]
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(
            @Value("${horriblechess.ratelimit.max-per-window:15}") int maxPerWindow,
            @Value("${horriblechess.ratelimit.window-millis:60000}") long windowMillis) {
        this(maxPerWindow, windowMillis, Clock.systemUTC());
    }

    // Visible for testing.
    RateLimiter(int maxPerWindow, long windowMillis, Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** Returns true if the action is allowed, false if the key is over its limit. */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) return true; // can't attribute — don't block
        long now = clock.millis();
        // compute() keeps the read-modify-write atomic per key.
        long[] result = windows.compute(key, (k, w) -> {
            if (w == null || now - w[0] >= windowMillis) {
                return new long[] {now, 1};
            }
            w[1]++;
            return w;
        });
        return result[1] <= maxPerWindow;
    }

    /** Drop windows that have fully elapsed so the map doesn't accumulate keys. */
    public void evictStale() {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMillis);
    }

    int trackedKeys() {
        return windows.size();
    }
}
