package com.horriblechess.controller;

import com.horriblechess.dto.GameStateDto;
import com.horriblechess.dto.JoinResponse;
import com.horriblechess.dto.MoveRequest;
import com.horriblechess.service.GameService;
import com.horriblechess.service.MoveExecutor;
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
    public JoinResponse create() {
        return gameService.createGame();
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
