package com.horriblechess.service;

import com.horriblechess.dto.JoinResponse;
import com.horriblechess.model.GameStatus;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class GameServiceSweepTest {

    static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long start) { this.millis = start; }
        void advance(long ms) { this.millis += ms; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private static final long MIN = 60_000L;

    private GameService newService(MutableClock clock, RateLimiter rl) {
        return new GameService(new MoveExecutor(), mock(SimpMessagingTemplate.class), rl, clock);
    }

    private RateLimiter generous(MutableClock clock) {
        return new RateLimiter(100_000, MIN, clock);
    }

    @Test
    void idleGameEvictedButRecentSurvives() {
        MutableClock clock = new MutableClock(0);
        GameService svc = newService(clock, generous(clock));

        JoinResponse a = svc.createGame("ip-a"); // touched at t=0
        clock.advance(30 * MIN);
        JoinResponse b = svc.createGame("ip-b"); // touched at t=30m
        clock.advance(31 * MIN);                 // now t=61m: A idle 61m, B idle 31m

        svc.sweep();

        assertNull(svc.peekGame(a.gameId()), "A idle > 60m should be evicted");
        assertEquals(1, svc.liveGameCount(), "B touched 31m ago should survive");
        assertEquals(GameStatus.WAITING_FOR_OPPONENT, svc.peekGame(b.gameId()).getStatus());
    }

    @Test
    void finishedGameEvictedOnShortGraceWhileIdleWaitingSurvives() {
        MutableClock clock = new MutableClock(0);
        GameService svc = newService(clock, generous(clock));

        JoinResponse finished = svc.createGame("ip-a");
        svc.peekGame(finished.gameId()).setStatus(GameStatus.WHITE_WINS);
        svc.peekGame(finished.gameId()).touch(0);
        JoinResponse waiting = svc.createGame("ip-b"); // WAITING, touched at t=0

        clock.advance(11 * MIN); // finished TTL is 10m; idle TTL is 60m

        svc.sweep();

        assertNull(svc.peekGame(finished.gameId()), "finished game past 10m grace should go");
        assertEquals(1, svc.liveGameCount(), "idle waiting game (11m < 60m) should remain");
    }

    @Test
    void createGameRejectedWhenRateLimited() {
        MutableClock clock = new MutableClock(0);
        RateLimiter strict = new RateLimiter(1, MIN, clock);
        GameService svc = newService(clock, strict);

        svc.createGame("spammer");
        assertThrows(GameService.RateLimitedException.class, () -> svc.createGame("spammer"));
    }
}
