package com.horriblechess.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long start) { this.millis = start; }
        void advance(long ms) { this.millis += ms; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    @Test
    void allowsUpToLimitThenBlocks() {
        MutableClock clock = new MutableClock(0);
        RateLimiter rl = new RateLimiter(3, 1000, clock);
        assertTrue(rl.tryAcquire("1.2.3.4"));
        assertTrue(rl.tryAcquire("1.2.3.4"));
        assertTrue(rl.tryAcquire("1.2.3.4"));
        assertFalse(rl.tryAcquire("1.2.3.4"), "4th in-window request should be blocked");
    }

    @Test
    void windowResetsAfterElapsed() {
        MutableClock clock = new MutableClock(0);
        RateLimiter rl = new RateLimiter(2, 1000, clock);
        assertTrue(rl.tryAcquire("ip"));
        assertTrue(rl.tryAcquire("ip"));
        assertFalse(rl.tryAcquire("ip"));
        clock.advance(1000); // window elapsed
        assertTrue(rl.tryAcquire("ip"), "new window should allow again");
    }

    @Test
    void keysAreIndependent() {
        RateLimiter rl = new RateLimiter(1, 1000, new MutableClock(0));
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("b"), "a different key has its own budget");
    }

    @Test
    void blankKeyIsNeverBlocked() {
        RateLimiter rl = new RateLimiter(1, 1000, new MutableClock(0));
        assertTrue(rl.tryAcquire(null));
        assertTrue(rl.tryAcquire(null));
        assertTrue(rl.tryAcquire(""));
    }

    @Test
    void evictStaleDropsElapsedWindows() {
        MutableClock clock = new MutableClock(0);
        RateLimiter rl = new RateLimiter(5, 1000, clock);
        rl.tryAcquire("a");
        rl.tryAcquire("b");
        clock.advance(1000);
        rl.evictStale();
        // Both windows have elapsed and should be gone.
        assertTrue(rl.trackedKeys() == 0);
    }
}
