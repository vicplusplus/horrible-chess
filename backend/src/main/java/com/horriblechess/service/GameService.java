package com.horriblechess.service;

import com.horriblechess.dto.GameStateDto;
import com.horriblechess.dto.JoinResponse;
import com.horriblechess.dto.MoveRequest;
import com.horriblechess.model.Board;
import com.horriblechess.model.Color;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.Move;
import com.horriblechess.model.Piece;
import com.horriblechess.model.PieceType;
import com.horriblechess.model.Position;
import com.horriblechess.model.RandomEvent;
import com.horriblechess.model.TurnAction;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final MoveExecutor moveExecutor;
    private final SimpMessagingTemplate broker;
    private final Random rng = new Random();

    public GameService(MoveExecutor moveExecutor, SimpMessagingTemplate broker) {
        this.moveExecutor = moveExecutor;
        this.broker = broker;
    }

    public JoinResponse createGame() {
        String id = shortId();
        Game game = new Game(id, Board.randomBackRowPosition(rng));
        games.put(id, game);
        String token = game.addPlayer();
        return new JoinResponse(id, token, game.colorOf(token));
    }

    public JoinResponse joinGame(String gameId) {
        Game game = games.get(gameId);
        if (game == null) throw new IllegalArgumentException("no such game");
        String token = game.addPlayer();
        if (token == null) throw new IllegalStateException("game is full");
        if (game.getStatus() == GameStatus.IN_PROGRESS) {
            rollFirstMover(game);
            broadcast(game);
            rollAndApplyAction(game, 0);
        }
        broadcast(game);
        return new JoinResponse(game.getId(), token, game.colorOf(token));
    }

    private void rollFirstMover(Game game) {
        Color first = rng.nextBoolean() ? Color.WHITE : Color.BLACK;
        game.setTurn(first);
        game.recordEvent(new RandomEvent(
                RandomEvent.EventKind.FIRST_MOVER,
                first == Color.WHITE ? "White" : "Black",
                List.of("White", "Black")));
    }

    private static final int MAX_AUTO_CHAIN = 4;

    private void rollAndApplyAction(Game game, int depth) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) return;
        TurnAction action = depth >= MAX_AUTO_CHAIN
                ? TurnAction.NORMAL
                : TurnAction.values()[rng.nextInt(TurnAction.values().length)];
        applyAction(game, action, depth);
    }

    private void applyAction(Game game, TurnAction action, int depth) {
        game.setCurrentTurnAction(action);
        game.setForcedPiecePosition(null);
        game.recordEvent(new RandomEvent(
                RandomEvent.EventKind.TURN_ACTION, action.label(), TurnAction.labels()));

        switch (action) {
            case NORMAL -> game.setMovesRemaining(1);
            case DOUBLE -> game.setMovesRemaining(2);
            case SKIP -> {
                game.setMovesRemaining(0);
                game.setTurn(game.getTurn().opposite());
                broadcast(game);
                rollAndApplyAction(game, depth + 1);
                return;
            }
            case FORCED -> {
                Position forced = pickForcedPiece(game);
                if (forced == null) {
                    applyAction(game, TurnAction.SKIP, depth);
                    return;
                }
                game.setMovesRemaining(1);
                game.setForcedPiecePosition(forced);
            }
            case AUTO -> {
                List<Move> moves = moveExecutor.legalMovesForColor(game, game.getTurn());
                if (moves.isEmpty()) {
                    applyAction(game, TurnAction.SKIP, depth);
                    return;
                }
                game.setMovesRemaining(1);
                broadcast(game);
                Move pick = moves.get(rng.nextInt(moves.size()));
                moveExecutor.applyTrusted(game, pick);
                broadcast(game);
                if (game.getStatus() != GameStatus.IN_PROGRESS) return;
                game.setCurrentTurnAction(null);
                game.setForcedPiecePosition(null);
                rollAndApplyAction(game, depth + 1);
            }
        }
    }

    private Position pickForcedPiece(Game game) {
        List<Position> candidates = new ArrayList<>();
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = game.getBoard().get(f, r);
                if (p == null || p.getColor() != game.getTurn()) continue;
                Position pos = new Position(f, r);
                if (!moveExecutor.legalMovesFromPosition(game, pos).isEmpty()) {
                    candidates.add(pos);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private void afterMove(Game game) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) return;
        int remaining = game.getMovesRemaining() - 1;
        if (remaining > 0 && game.getCurrentTurnAction() == TurnAction.DOUBLE) {
            // Stay on the same player for the second move.
            game.setTurn(game.getTurn().opposite());
            game.setMovesRemaining(remaining);
            game.setForcedPiecePosition(null);
            return;
        }
        game.setMovesRemaining(0);
        game.setCurrentTurnAction(null);
        game.setForcedPiecePosition(null);
        rollAndApplyAction(game, 0);
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
            afterMove(game);
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
