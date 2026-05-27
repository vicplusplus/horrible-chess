package com.horriblechess.service;

import com.horriblechess.dto.GameStateDto;
import com.horriblechess.dto.JoinResponse;
import com.horriblechess.dto.MoveRequest;
import com.horriblechess.model.Board;
import com.horriblechess.model.Color;
import com.horriblechess.model.Duck;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.Move;
import com.horriblechess.model.Piece;
import com.horriblechess.model.PieceType;
import com.horriblechess.model.Position;
import com.horriblechess.model.RandomEvent;
import com.horriblechess.model.SquareEvent;
import com.horriblechess.model.TurnAction;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
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

    private static final int MAX_AUTO_CHAIN = 4;
    private static final int MAX_EVENT_SQUARES = 3;

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
            refreshEventSquares(game);
            rollAndApplyAction(game, 0);
        }
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
            afterMove(game, move);
            broadcast(game);
        }
        return outcome;
    }

    // ---- Turn lifecycle ----

    private void rollFirstMover(Game game) {
        Color first = rng.nextBoolean() ? Color.WHITE : Color.BLACK;
        game.setTurn(first);
        game.recordEvent(new RandomEvent(
                RandomEvent.EventKind.FIRST_MOVER,
                first == Color.WHITE ? "White" : "Black",
                List.of("White", "Black")));
    }

    private void afterMove(Game game, Move lastMove) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) return;

        // Event square trigger from the move that just landed.
        triggerEventSquareIfApplicable(game, lastMove);
        if (game.getStatus() != GameStatus.IN_PROGRESS) return;

        // DOUBLE turn: stay on the same player for the second move.
        int remaining = game.getMovesRemaining() - 1;
        if (remaining > 0 && game.getCurrentTurnAction() == TurnAction.DOUBLE) {
            game.setTurn(game.getTurn().opposite());
            game.setMovesRemaining(remaining);
            game.setForcedPiecePosition(null);
            return;
        }

        // Tick ducks and pick new event squares for the next turn.
        decrementDucks(game);
        refreshEventSquares(game);

        game.setMovesRemaining(0);
        game.setCurrentTurnAction(null);
        game.setForcedPiecePosition(null);

        rollAndApplyAction(game, 0);
    }

    private void rollAndApplyAction(Game game, int depth) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) return;

        // Honor a pending skip from a SKIP_TURN square event.
        if (game.getPendingSkip() == game.getTurn()) {
            game.setPendingSkip(null);
            applyAction(game, TurnAction.SKIP, depth);
            return;
        }

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
                afterMove(game, pick);
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

    // ---- Event squares & ducks ----

    private void triggerEventSquareIfApplicable(Game game, Move move) {
        Position to = move.to();
        Piece landed = game.getBoard().get(to);
        if (landed == null) return; // standoff resolved without a piece on the square
        if (!game.getEventSquares().contains(to)) return;

        game.getEventSquares().remove(to);
        SquareEvent event = SquareEvent.values()[rng.nextInt(SquareEvent.values().length)];
        game.recordEvent(new RandomEvent(
                RandomEvent.EventKind.SQUARE_EVENT, event.label(), SquareEvent.labels()));
        broadcast(game);
        applyEvent(game, event, to);
    }

    private void applyEvent(Game game, SquareEvent event, Position landingPos) {
        switch (event) {
            case RANDOM_CAPTURE -> applyRandomCapture(game);
            case PIECE_SPAWN -> applyPieceSpawn(game);
            case SKIP_TURN -> game.setPendingSkip(game.getTurn());
            case COLOR_SWAP -> applyColorSwap(game);
            case RANDOM_MOVE -> applyRandomMove(game);
            case EXPLOSION -> applyExplosion(game, landingPos);
            case DUCK -> applyDuckSpawn(game);
        }
    }

    private void applyRandomCapture(Game game) {
        List<Position> candidates = new ArrayList<>();
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = game.getBoard().get(f, r);
                if (p == null) continue;
                // Exclude a king that's the last of its color.
                if (p.getType() == PieceType.KING && countKings(game, p.getColor()) == 1) continue;
                candidates.add(new Position(f, r));
            }
        }
        if (candidates.isEmpty()) return;
        Position pick = candidates.get(rng.nextInt(candidates.size()));
        game.getBoard().set(pick, null);
    }

    private void applyPieceSpawn(Game game) {
        Position empty = randomEmptySquare(game);
        if (empty == null) return;
        PieceType[] types = {
                PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK,
                PieceType.QUEEN, PieceType.KING
        };
        PieceType type = types[rng.nextInt(types.length)];
        Color color = rng.nextBoolean() ? Color.WHITE : Color.BLACK;
        Piece p = new Piece(type, color);
        p.markMoved();
        game.getBoard().set(empty, p);
    }

    private void applyColorSwap(Game game) {
        List<Position> candidates = piecesOnBoard(game);
        if (candidates.isEmpty()) return;
        Position pos = candidates.get(rng.nextInt(candidates.size()));
        Piece old = game.getBoard().get(pos);
        Piece swapped = new Piece(old.getType(), old.getColor().opposite());
        if (old.hasMoved()) swapped.markMoved();
        game.getBoard().set(pos, swapped);
        if (old.getType() == PieceType.KING && countKings(game, old.getColor()) == 0) {
            game.setStatus(old.getColor() == Color.WHITE
                    ? GameStatus.BLACK_WINS : GameStatus.WHITE_WINS);
        }
    }

    private void applyRandomMove(Game game) {
        List<Position> candidates = piecesOnBoard(game);
        Collections.shuffle(candidates, rng);
        for (Position from : candidates) {
            Piece p = game.getBoard().get(from);
            Color savedTurn = game.getTurn();
            game.setTurn(p.getColor());
            List<Move> moves = moveExecutor.legalMovesFromPosition(game, from);
            game.setTurn(savedTurn);
            if (moves.isEmpty()) continue;
            Move pick = moves.get(rng.nextInt(moves.size()));
            applyRawMove(game, p, pick);
            return;
        }
    }

    private void applyRawMove(Game game, Piece piece, Move m) {
        Position from = m.from();
        Position to = m.to();
        Piece captured = game.getBoard().get(to);
        game.getBoard().set(from, null);
        if (piece.getType() == PieceType.PAWN && (to.rank() == 0 || to.rank() == 7)) {
            // Quiet auto-promote to queen; this is a meta-event move, not a player ply.
            Piece queen = new Piece(PieceType.QUEEN, piece.getColor());
            queen.markMoved();
            game.getBoard().set(to, queen);
        } else {
            game.getBoard().set(to, piece);
            piece.markMoved();
        }
        if (captured != null && captured.getType() == PieceType.KING
                && countKings(game, captured.getColor()) == 0) {
            game.setStatus(captured.getColor() == Color.WHITE
                    ? GameStatus.BLACK_WINS : GameStatus.WHITE_WINS);
        }
    }

    private void applyExplosion(Game game, Position center) {
        for (int df = -1; df <= 1; df++) {
            for (int dr = -1; dr <= 1; dr++) {
                Position p = new Position(center.file() + df, center.rank() + dr);
                if (!p.onBoard()) continue;
                if (game.getBoard().get(p) != null) game.getBoard().set(p, null);
            }
        }
        for (Color c : Color.values()) {
            if (countKings(game, c) == 0) {
                game.setStatus(c == Color.WHITE ? GameStatus.BLACK_WINS : GameStatus.WHITE_WINS);
                return;
            }
        }
    }

    private void applyDuckSpawn(Game game) {
        Position empty = randomEmptySquare(game);
        if (empty == null) return;
        int turns = 2 + rng.nextInt(5); // 2..6 turns
        game.getDucks().add(new Duck(empty, turns));
    }

    private void refreshEventSquares(Game game) {
        game.getEventSquares().clear();
        List<Position> empties = emptySquares(game);
        if (empties.isEmpty()) return;
        Collections.shuffle(empties, rng);
        int n = Math.min(empties.size(), rng.nextInt(MAX_EVENT_SQUARES + 1));
        for (int i = 0; i < n; i++) game.getEventSquares().add(empties.get(i));
    }

    private void decrementDucks(Game game) {
        List<Duck> updated = new ArrayList<>();
        for (Duck d : game.getDucks()) {
            int n = d.turnsRemaining() - 1;
            if (n > 0) updated.add(d.withTurnsRemaining(n));
        }
        game.getDucks().clear();
        game.getDucks().addAll(updated);
    }

    // ---- Board helpers ----

    private List<Position> emptySquares(Game game) {
        List<Position> empties = new ArrayList<>();
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Position pos = new Position(f, r);
                if (game.getBoard().get(pos) != null) continue;
                if (isDuckSquare(game, pos)) continue;
                empties.add(pos);
            }
        }
        return empties;
    }

    private Position randomEmptySquare(Game game) {
        List<Position> empties = emptySquares(game);
        if (empties.isEmpty()) return null;
        return empties.get(rng.nextInt(empties.size()));
    }

    private List<Position> piecesOnBoard(Game game) {
        List<Position> result = new ArrayList<>();
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                if (game.getBoard().get(f, r) != null) result.add(new Position(f, r));
            }
        }
        return result;
    }

    private boolean isDuckSquare(Game game, Position pos) {
        for (Duck d : game.getDucks()) {
            if (d.position().equals(pos)) return true;
        }
        return false;
    }

    private int countKings(Game game, Color color) {
        int n = 0;
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = game.getBoard().get(f, r);
                if (p != null && p.getType() == PieceType.KING && p.getColor() == color) n++;
            }
        }
        return n;
    }

    // ---- Plumbing ----

    private void broadcast(Game game) {
        broker.convertAndSend("/topic/game/" + game.getId(), GameStateDto.from(game));
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
