package com.horriblechess.service;

import com.horriblechess.dto.GameStateDto;
import com.horriblechess.dto.JoinResponse;
import com.horriblechess.dto.MoveRequest;
import com.horriblechess.model.Game;
import com.horriblechess.model.Move;
import com.horriblechess.model.PieceType;
import com.horriblechess.model.Position;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final MoveExecutor moveExecutor;
    private final SimpMessagingTemplate broker;

    public GameService(MoveExecutor moveExecutor, SimpMessagingTemplate broker) {
        this.moveExecutor = moveExecutor;
        this.broker = broker;
    }

    public JoinResponse createGame() {
        String id = shortId();
        Game game = new Game(id);
        games.put(id, game);
        String token = game.addPlayer();
        return new JoinResponse(id, token, game.colorOf(token));
    }

    public JoinResponse joinGame(String gameId) {
        Game game = games.get(gameId);
        if (game == null) throw new IllegalArgumentException("no such game");
        String token = game.addPlayer();
        if (token == null) throw new IllegalStateException("game is full");
        broadcast(game);
        return new JoinResponse(game.getId(), token, game.colorOf(token));
    }

    public GameStateDto getState(String gameId) {
        Game game = games.get(gameId);
        if (game == null) throw new IllegalArgumentException("no such game");
        return GameStateDto.from(game);
    }

    public MoveExecutor.Outcome submitMove(String gameId, MoveRequest req) {
        Game game = games.get(gameId);
        if (game == null) return MoveExecutor.Outcome.err("no such game");
        Move move = new Move(
                new Position(req.fromFile(), req.fromRank()),
                new Position(req.toFile(), req.toRank()),
                req.promotion() == null ? null : PieceType.valueOf(req.promotion()));
        MoveExecutor.Outcome outcome = moveExecutor.apply(game, move, req.playerId());
        if (outcome.ok()) {
            broadcast(game);
        }
        return outcome;
    }

    private void broadcast(Game game) {
        broker.convertAndSend("/topic/game/" + game.getId(), GameStateDto.from(game));
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
