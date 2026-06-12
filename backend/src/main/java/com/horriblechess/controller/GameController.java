package com.horriblechess.controller;

import com.horriblechess.dto.GameStateDto;
import com.horriblechess.dto.JoinResponse;
import com.horriblechess.dto.MoveRequest;
import com.horriblechess.service.GameService;
import com.horriblechess.service.MoveExecutor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gameService.createGame(clientKey(request)));
        } catch (GameService.RateLimitedException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        } catch (GameService.CapacityException e) {
            return ResponseEntity.status(503).body(e.getMessage());
        }
    }

    /** Best-effort client identity for rate limiting: first X-Forwarded-For hop
     *  (we sit behind nginx) falling back to the socket address. */
    private static String clientKey(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma >= 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/{gameId}/join")
    public ResponseEntity<?> join(@PathVariable String gameId) {
        try {
            return ResponseEntity.ok(gameService.joinGame(gameId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        }
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<?> state(@PathVariable String gameId) {
        try {
            GameStateDto state = gameService.getState(gameId);
            return ResponseEntity.ok(state);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }

    @GetMapping("/{gameId}/frames")
    public ResponseEntity<?> frames(@PathVariable String gameId,
                                    @RequestParam(defaultValue = "-1") long since) {
        try {
            return ResponseEntity.ok(gameService.getFrames(gameId, since));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }

    @PostMapping("/{gameId}/move")
    public ResponseEntity<?> move(@PathVariable String gameId, @RequestBody MoveRequest req) {
        MoveExecutor.Outcome outcome = gameService.submitMove(gameId, req);
        if (outcome.ok()) {
            return ResponseEntity.ok(gameService.getState(gameId));
        }
        return ResponseEntity.badRequest().body(outcome.error());
    }
}
