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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
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
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final Random rng = new Random();

    private static final int MAX_AUTO_CHAIN = 4;
    private static final int MAX_EVENT_SQUARES = 3;
    // Multi-piece square events (color swap, spawn, random capture) affect 1..N
    // pieces; ducks land 1..N at a time. Counts are picked with a descending
    // weight (see randomCount) so larger swings stay rare.
    private static final int MULTI_PIECE_MAX = 4;
    private static final int MAX_DUCKS_PER_EVENT = 3;

    // Eviction policy. Finished games linger briefly so both players can read
    // the result; abandoned/idle games (waiting or mid-match) get a longer grace.
    private static final int MAX_GAMES = 1000;
    private static final long FINISHED_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final long IDLE_TTL_MS = Duration.ofMinutes(60).toMillis();

    /** Thrown when a client creates games faster than the per-IP limit allows. */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String m) { super(m); }
    }
    /** Thrown when the server is holding the maximum number of live games. */
    public static class CapacityException extends RuntimeException {
        public CapacityException(String m) { super(m); }
    }

    @Autowired
    public GameService(MoveExecutor moveExecutor, SimpMessagingTemplate broker,
                       RateLimiter rateLimiter) {
        this(moveExecutor, broker, rateLimiter, Clock.systemUTC());
    }

    // Visible for testing (injectable clock).
    GameService(MoveExecutor moveExecutor, SimpMessagingTemplate broker,
                RateLimiter rateLimiter, Clock clock) {
        this.moveExecutor = moveExecutor;
        this.broker = broker;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public JoinResponse createGame(String clientKey) {
        if (!rateLimiter.tryAcquire(clientKey)) {
            throw new RateLimitedException("Too many games created from here. Try again in a minute.");
        }
        // Opportunistic cleanup so a burst of finished/idle games frees room
        // before we reject on capacity.
        if (games.size() >= MAX_GAMES) sweep();
        if (games.size() >= MAX_GAMES) {
            throw new CapacityException("Server is at capacity. Please try again later.");
        }
        String id = shortId();
        Game game = new Game(id, Board.randomBackRowPosition(rng));
        game.touch(clock.millis());
        games.put(id, game);
        String token = game.addPlayer();
        return new JoinResponse(id, token, game.colorOf(token));
    }

    public JoinResponse joinGame(String gameId) {
        Game game = games.get(gameId);
        if (game == null) throw new IllegalArgumentException("no such game");
        String token = game.addPlayer();
        if (token == null) throw new IllegalStateException("game is full");
        game.touch(clock.millis());
        if (game.getStatus() == GameStatus.IN_PROGRESS) {
            rollFirstMover(game);
            broadcast(game);
            refreshEventSquares(game);
            rollAndApplyAction(game, 0);
        }
        broadcast(game);
        return new JoinResponse(game.getId(), token, game.colorOf(token));
    }

    /**
     * Evict finished games past their short grace period and idle/abandoned
     * games past the longer one, then drop stale rate-limit windows. Runs on a
     * timer and opportunistically when we hit the capacity ceiling.
     */
    @Scheduled(fixedDelayString = "${horriblechess.sweep.interval-millis:300000}")
    public void sweep() {
        long now = clock.millis();
        games.entrySet().removeIf(e -> {
            if (!isExpired(e.getValue(), now)) return false;
            frameLog.remove(e.getKey()); // drop the evicted game's frame log too
            return true;
        });
        rateLimiter.evictStale();
    }

    private boolean isExpired(Game game, long now) {
        long idleMs = now - game.getLastTouched();
        boolean finished = game.getStatus() == GameStatus.WHITE_WINS
                || game.getStatus() == GameStatus.BLACK_WINS;
        return idleMs > (finished ? FINISHED_TTL_MS : IDLE_TTL_MS);
    }

    int liveGameCount() {
        return games.size();
    }

    // Visible for testing.
    Game peekGame(String id) {
        return games.get(id);
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
        List<Position> forced = game.getForcedPiecePositions();
        if (!forced.isEmpty()) {
            List<Move> moves = new ArrayList<>();
            for (Position p : forced) moves.addAll(moveExecutor.legalMovesFromPosition(game, p));
            return moves;
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
            game.touch(clock.millis());
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
            game.clearForcedPieces();
            return;
        }

        // Tick ducks once per full round: only when the OTHER side has already
        // completed a real move since the last tick. SKIPs don't reach here, so
        // they're naturally excluded; DOUBLE early-returns above and registers
        // as a single mover for tick purposes.
        Color justMoved = game.getTurn().opposite();
        Color pendingTick = game.getDuckTickPendingSide();
        if (pendingTick == null) {
            game.setDuckTickPendingSide(justMoved);
        } else if (pendingTick != justMoved) {
            decrementDucks(game);
            game.setDuckTickPendingSide(null);
        }
        refreshEventSquares(game);

        game.setMovesRemaining(0);
        game.setCurrentTurnAction(null);
        game.clearForcedPieces();

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
                : TurnAction.randomWeighted(rng);
        applyAction(game, action, depth);
    }

    private void applyAction(Game game, TurnAction action, int depth) {
        game.setCurrentTurnAction(action);
        game.clearForcedPieces();
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
                List<Position> forced = pickForcedPieces(game);
                if (forced.isEmpty()) {
                    applyAction(game, TurnAction.SKIP, depth);
                    return;
                }
                game.setMovesRemaining(1);
                game.setForcedPiecePositions(forced);
                StringBuilder list = new StringBuilder();
                for (int i = 0; i < forced.size(); i++) {
                    Position p = forced.get(i);
                    Piece fp = game.getBoard().get(p);
                    if (i > 0) list.append(", ");
                    list.append(Notation.glyph(actingColor, fp.getType()))
                            .append(" ").append(Notation.pieceName(fp.getType()))
                            .append(" at ").append(Notation.square(p));
                }
                game.log(JournalEntry.JournalKind.TURN, actingColor,
                        Notation.side(actingColor) + "'s turn — forced: must move one of "
                                + forced.size() + " piece" + (forced.size() == 1 ? "" : "s")
                                + " (" + list + ").");
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

    private List<Position> pickForcedPieces(Game game) {
        // Only consider pieces that actually have a legal move — never stick
        // the player with pieces that can't move.
        List<Position> candidates = moveExecutor.movablePieces(game, game.getTurn());
        if (candidates.isEmpty()) return List.of();
        Collections.shuffle(candidates, rng);
        int n = Math.min(candidates.size(), 1 + rng.nextInt(4)); // 1..4 pieces
        return new ArrayList<>(candidates.subList(0, n));
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
        // Surface the event's board result as its own frame, tied to the same
        // event (no new eventSeq). Otherwise the mutation gets bundled into the
        // *next* event's held-back snapshot, so spawns/swaps/captures only become
        // visible once that following event finishes revealing.
        broadcast(game);
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
        int target = randomCount(MULTI_PIECE_MAX);
        int removed = 0;
        // Re-pick each round: last-king eligibility shifts as pieces leave.
        for (int i = 0; i < target && removeOneRandomPiece(game); i++) removed++;
        if (removed == 0) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Random capture had no eligible target.");
        }
    }

    private boolean removeOneRandomPiece(Game game) {
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
        if (candidates.isEmpty()) return false;
        Position pick = candidates.get(rng.nextInt(candidates.size()));
        Piece victim = game.getBoard().get(pick);
        game.getBoard().set(pick, null);
        game.log(JournalEntry.JournalKind.SQUARE_EVENT, victim.getColor(),
                "Random capture removes " + Notation.sidePossessive(victim.getColor())
                        + " " + Notation.pieceName(victim.getType()) + " "
                        + Notation.glyph(victim.getColor(), victim.getType())
                        + " at " + Notation.square(pick) + ".");
        return true;
    }

    private void applyPieceSpawn(Game game) {
        int target = randomCount(MULTI_PIECE_MAX);
        int spawned = 0;
        for (int i = 0; i < target && spawnOnePiece(game); i++) spawned++;
        if (spawned == 0) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Piece spawn fizzled — no empty square.");
        }
    }

    private boolean spawnOnePiece(Game game) {
        Position empty = randomEmptySquare(game);
        if (empty == null) return false;
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
        return true;
    }

    private void applyColorSwap(Game game) {
        List<Position> candidates = piecesOnBoard(game);
        if (candidates.isEmpty()) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Color swap had no piece to flip.");
            return;
        }
        Collections.shuffle(candidates, rng);
        int n = Math.min(candidates.size(), randomCount(MULTI_PIECE_MAX));
        for (int i = 0; i < n; i++) {
            Position pos = candidates.get(i);
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
        }
        // A swap can flip the last king of a color (or both); whoever is left
        // kingless loses. Check after all flips settle.
        for (Color c : Color.values()) {
            if (countKings(game, c) == 0) {
                game.setStatus(c == Color.WHITE ? GameStatus.BLACK_WINS : GameStatus.WHITE_WINS);
                return;
            }
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
        int target = randomCount(MAX_DUCKS_PER_EVENT);
        int landed = 0;
        for (int i = 0; i < target; i++) {
            // randomEmptySquare skips existing duck squares, so each lands fresh.
            Position empty = randomEmptySquare(game);
            if (empty == null) break;
            int turns = 2 + rng.nextInt(5); // 2..6 turns
            game.getDucks().add(new Duck(empty, turns));
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "🦆 Duck lands at " + Notation.square(empty)
                            + " — blocks for " + turns + " turns.");
            landed++;
        }
        if (landed == 0) {
            game.log(JournalEntry.JournalKind.SQUARE_EVENT, null,
                    "Duck had nowhere to land.");
        }
    }

    /**
     * Pick a count in 1..max with linearly descending weight: count {@code k}
     * has weight {@code max - k + 1}, so smaller counts are commoner and larger
     * ones get progressively rarer (max=3 → 1:1/2, 2:1/3, 3:1/6). Shared by the
     * multi-piece square events and duck spawns.
     */
    private int randomCount(int max) {
        int total = max * (max + 1) / 2; // sum of weights max + (max-1) + ... + 1
        int roll = rng.nextInt(total);
        for (int k = 1; k <= max; k++) {
            roll -= (max - k + 1);
            if (roll < 0) return k;
        }
        return 1; // unreachable
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
