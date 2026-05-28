package com.horriblechess.service;

import com.horriblechess.dto.GameStateDto;
import com.horriblechess.dto.JoinResponse;
import com.horriblechess.dto.MoveRequest;
import com.horriblechess.model.Board;
import com.horriblechess.model.Color;
import com.horriblechess.model.Duck;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.JournalEntry;
import com.horriblechess.model.Move;
import com.horriblechess.model.Notation;
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
    // Append-only log of every broadcast frame per game. Lets a client that
    // missed broadcasts (backgrounded tab, dropped socket) refetch the frames
    // since its last seen frameSeq and replay them, rather than snapping.
    private final Map<String, List<GameStateDto>> frameLog = new ConcurrentHashMap<>();
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
        List<GameStateDto> log = frameLog.get(gameId);
        if (log != null) {
            synchronized (log) {
                if (!log.isEmpty()) return log.get(log.size() - 1);
            }
        }
        // No frame broadcast yet (e.g. game created, still waiting). frameSeq -1
        // so the first real frame (0) is newer and gets replayed by the client.
        return GameStateDto.from(game, legalMovesForView(game), -1);
    }

    // Frames newer than `since` (frameSeq > since), in order, for catch-up replay.
    public List<GameStateDto> getFrames(String gameId, long since) {
        if (!games.containsKey(gameId)) throw new IllegalArgumentException("no such game");
        List<GameStateDto> log = frameLog.get(gameId);
        if (log == null) return List.of();
        List<GameStateDto> out = new ArrayList<>();
        synchronized (log) {
            for (GameStateDto f : log) {
                if (f.frameSeq() > since) out.add(f);
            }
        }
        return out;
    }

    private GameStateDto buildDto(Game game, long frameSeq) {
        return GameStateDto.from(game, legalMovesForView(game), frameSeq);
    }

    private List<Move> legalMovesForView(Game game) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) return List.of();
        TurnAction action = game.getCurrentTurnAction();
        if (action == TurnAction.SKIP || action == TurnAction.AUTO) return List.of();
        if (game.getForcedPiecePosition() != null) {
            return moveExecutor.legalMovesFromPosition(game, game.getForcedPiecePosition());
        }
        return moveExecutor.legalMovesForColor(game, game.getTurn());
    }

    public MoveExecutor.Outcome submitMove(String gameId, MoveRequest req) {
        Game game = games.get(gameId);
        if (game == null) return MoveExecutor.Outcome.err("no such game");
        Move move = new Move(
                new Position(req.fromFile(), req.fromRank()),
                new Position(req.toFile(), req.toRank()),
                req.promotion() == null ? null : PieceType.valueOf(req.promotion()));
        GameStatus statusBefore = game.getStatus();
        MoveExecutor.Outcome outcome = moveExecutor.apply(game, move, req.playerId());
        if (outcome.ok()) {
            logGameOverIfNew(game, statusBefore);
            broadcast(game);
            afterMove(game, move);
            broadcast(game);
        }
        return outcome;
    }

    private void logGameOverIfNew(Game game, GameStatus before) {
        if (before == game.getStatus()) return;
        if (game.getStatus() == GameStatus.WHITE_WINS) {
            game.log(JournalEntry.JournalKind.GAME, null, "Game over — White wins.");
        } else if (game.getStatus() == GameStatus.BLACK_WINS) {
            game.log(JournalEntry.JournalKind.GAME, null, "Game over — Black wins.");
        }
    }

    // ---- Turn lifecycle ----

    private void rollFirstMover(Game game) {
        Color first = rng.nextBoolean() ? Color.WHITE : Color.BLACK;
        game.setTurn(first);
        game.recordEvent(new RandomEvent(
                RandomEvent.EventKind.FIRST_MOVER,
                first == Color.WHITE ? "White" : "Black",
                List.of("White", "Black")));
        game.log(JournalEntry.JournalKind.GAME, first,
                Notation.side(first) + " moves first.");
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
        // Capture the acting side before recording — SKIP flips the turn below,
        // so deriving it from a later broadcast would name the wrong player.
        Color actingColor = game.getTurn();
        game.recordEvent(new RandomEvent(
                RandomEvent.EventKind.TURN_ACTION, action.label(), TurnAction.labels(), actingColor));

        switch (action) {
            case NORMAL -> {
                game.setMovesRemaining(1);
                game.log(JournalEntry.JournalKind.TURN, actingColor,
                        Notation.side(actingColor) + "'s turn — normal move.");
            }
            case DOUBLE -> {
                game.setMovesRemaining(2);
                game.log(JournalEntry.JournalKind.TURN, actingColor,
                        Notation.side(actingColor) + "'s turn — double turn (2 moves).");
            }
            case SKIP -> {
                game.setMovesRemaining(0);
                game.log(JournalEntry.JournalKind.TURN, actingColor,
                        Notation.side(actingColor) + "'s turn — skipped.");
                game.setTurn(actingColor.opposite());
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
                Piece fp = game.getBoard().get(forced);
                game.log(JournalEntry.JournalKind.TURN, actingColor,
                        Notation.side(actingColor) + "'s turn — forced piece: must move "
                                + Notation.glyph(actingColor, fp.getType())
                                + " " + Notation.pieceName(fp.getType())
                                + " at " + Notation.square(forced) + ".");
            }
            case AUTO -> {
                List<Move> moves = moveExecutor.legalMovesForColor(game, game.getTurn());
                if (moves.isEmpty()) {
                    applyAction(game, TurnAction.SKIP, depth);
                    return;
                }
                game.setMovesRemaining(1);
                game.log(JournalEntry.JournalKind.TURN, actingColor,
                        Notation.side(actingColor) + "'s turn — auto-move (board picks).");
                broadcast(game);
                Move pick = moves.get(rng.nextInt(moves.size()));
                GameStatus statusBefore = game.getStatus();
                moveExecutor.applyTrusted(game, pick);
                logGameOverIfNew(game, statusBefore);
                broadcast(game);
                if (game.getStatus() != GameStatus.IN_PROGRESS) return;
                afterMove(game, pick);
            }
        }
    }

    private Position pickForcedPiece(Game game) {
        // Only consider pieces that actually have a legal move — never stick
        // the player with a piece that can't move.
        List<Position> candidates = moveExecutor.movablePieces(game, game.getTurn());
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
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                "? square at " + Notation.square(to) + " triggered — " + event.label() + ".");
        broadcast(game);
        GameStatus statusBefore = game.getStatus();
        applyEvent(game, event, to);
        logGameOverIfNew(game, statusBefore);
    }

    private void applyEvent(Game game, SquareEvent event, Position landingPos) {
        switch (event) {
            case RANDOM_CAPTURE -> applyRandomCapture(game);
            case PIECE_SPAWN -> applyPieceSpawn(game);
            case SKIP_TURN -> {
                game.setPendingSkip(game.getTurn());
                game.log(JournalEntry.JournalKind.SQUARE_EVENT, game.getTurn(),
                        Notation.sidePossessive(game.getTurn()) + " next turn will be skipped.");
            }
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
        if (candidates.isEmpty()) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Random capture had no eligible target.");
            return;
        }
        Position pick = candidates.get(rng.nextInt(candidates.size()));
        Piece victim = game.getBoard().get(pick);
        game.getBoard().set(pick, null);
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, victim.getColor(),
                "Random capture removes " + Notation.sidePossessive(victim.getColor())
                        + " " + Notation.pieceName(victim.getType()) + " "
                        + Notation.glyph(victim.getColor(), victim.getType())
                        + " at " + Notation.square(pick) + ".");
    }

    private void applyPieceSpawn(Game game) {
        Position empty = randomEmptySquare(game);
        if (empty == null) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Piece spawn fizzled — no empty square.");
            return;
        }
        PieceType[] types = {
                PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK,
                PieceType.QUEEN, PieceType.KING
        };
        PieceType type = types[rng.nextInt(types.length)];
        Color color = rng.nextBoolean() ? Color.WHITE : Color.BLACK;
        Piece p = new Piece(type, color);
        p.markMoved();
        game.getBoard().set(empty, p);
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, color,
                "Spawned " + Notation.sideLower(color) + " " + Notation.pieceName(type)
                        + " " + Notation.glyph(color, type)
                        + " at " + Notation.square(empty) + ".");
    }

    private void applyColorSwap(Game game) {
        List<Position> candidates = piecesOnBoard(game);
        if (candidates.isEmpty()) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Color swap had no piece to flip.");
            return;
        }
        Position pos = candidates.get(rng.nextInt(candidates.size()));
        Piece old = game.getBoard().get(pos);
        Piece swapped = new Piece(old.getType(), old.getColor().opposite());
        if (old.hasMoved()) swapped.markMoved();
        game.getBoard().set(pos, swapped);
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, swapped.getColor(),
                Notation.sidePossessive(old.getColor()) + " " + Notation.pieceName(old.getType())
                        + " " + Notation.glyph(old.getColor(), old.getType())
                        + " at " + Notation.square(pos)
                        + " switches sides → " + Notation.glyph(swapped.getColor(), swapped.getType())
                        + ".");
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
            Piece victim = game.getBoard().get(pick.to());
            applyRawMove(game, p, pick);
            StringBuilder sb = new StringBuilder();
            sb.append("Random move: ").append(Notation.sidePossessive(p.getColor()))
                    .append(" ").append(Notation.pieceName(p.getType()))
                    .append(" ").append(Notation.glyph(p.getColor(), p.getType()))
                    .append(" ").append(Notation.square(from))
                    .append(" → ").append(Notation.square(pick.to()));
            if (victim != null) {
                sb.append(" takes ").append(Notation.glyph(victim.getColor(), victim.getType()))
                        .append(" ").append(Notation.pieceName(victim.getType()));
            }
            sb.append(".");
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, p.getColor(), sb.toString());
            return;
        }
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                "Random move had no legal move available.");
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
        int destroyed = 0;
        for (int df = -1; df <= 1; df++) {
            for (int dr = -1; dr <= 1; dr++) {
                Position p = new Position(center.file() + df, center.rank() + dr);
                if (!p.onBoard()) continue;
                if (game.getBoard().get(p) != null) {
                    game.getBoard().set(p, null);
                    destroyed++;
                }
            }
        }
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                "Explosion at " + Notation.square(center)
                        + " wipes " + destroyed + " piece" + (destroyed == 1 ? "" : "s") + ".");
        for (Color c : Color.values()) {
            if (countKings(game, c) == 0) {
                game.setStatus(c == Color.WHITE ? GameStatus.BLACK_WINS : GameStatus.WHITE_WINS);
                return;
            }
        }
    }

    private void applyDuckSpawn(Game game) {
        Position empty = randomEmptySquare(game);
        if (empty == null) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Duck had nowhere to land.");
            return;
        }
        int turns = 2 + rng.nextInt(5); // 2..6 turns
        game.getDucks().add(new Duck(empty, turns));
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                "🦆 Duck lands at " + Notation.square(empty)
                        + " — blocks for " + turns + " turns.");
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
        List<GameStateDto> log = frameLog.computeIfAbsent(
                game.getId(), k -> Collections.synchronizedList(new ArrayList<>()));
        GameStateDto dto;
        synchronized (log) {
            dto = buildDto(game, log.size());
            log.add(dto);
        }
        broker.convertAndSend("/topic/game/" + game.getId(), dto);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
