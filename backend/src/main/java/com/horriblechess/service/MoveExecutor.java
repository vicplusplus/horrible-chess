package com.horriblechess.service;

import com.horriblechess.model.Board;
import com.horriblechess.model.CaptureOutcome;
import com.horriblechess.model.Color;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.JournalEntry;
import com.horriblechess.model.Move;
import com.horriblechess.model.Notation;
import com.horriblechess.model.Piece;
import com.horriblechess.model.PieceType;
import com.horriblechess.model.Position;
import com.horriblechess.model.PromotionOutcome;
import com.horriblechess.model.RandomEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@Component
public class MoveExecutor {

    public enum SpecialKind { NORMAL, DOUBLE_PAWN, EN_PASSANT, CASTLE_KINGSIDE, CASTLE_QUEENSIDE, PROMOTION }

    public record Outcome(boolean ok, String error) {
        public static Outcome success() { return new Outcome(true, null); }
        public static Outcome err(String m) { return new Outcome(false, m); }
    }

    private static final List<String> PROMOTION_LABELS =
            Arrays.stream(PromotionOutcome.values()).map(PromotionOutcome::label).toList();

    private final Random rng;

    public MoveExecutor() { this(new Random()); }
    public MoveExecutor(Random rng) { this.rng = rng; }

    public Outcome apply(Game game, Move move, String playerId) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return Outcome.err("game is not in progress");
        }
        Color playerColor = game.colorOf(playerId);
        if (playerColor == null) return Outcome.err("not a player in this game");
        if (playerColor != game.getTurn()) return Outcome.err("not your turn");
        if (game.getCurrentTurnAction() == com.horriblechess.model.TurnAction.SKIP) {
            return Outcome.err("your turn was skipped");
        }
        if (game.getCurrentTurnAction() == com.horriblechess.model.TurnAction.AUTO) {
            return Outcome.err("auto-move in progress");
        }
        if (!game.getForcedPiecePositions().isEmpty()
                && !game.getForcedPiecePositions().contains(move.from())) {
            return Outcome.err("must move one of the highlighted pieces");
        }
        return applyTrusted(game, move);
    }

    public Outcome applyTrusted(Game game, Move move) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return Outcome.err("game is not in progress");
        }
        Position from = move.from();
        Position to = move.to();
        if (from == null || to == null || !from.onBoard() || !to.onBoard()) {
            return Outcome.err("position off board");
        }
        if (from.equals(to)) return Outcome.err("must move to a different square");

        Board board = game.getBoard();
        Piece piece = board.get(from);
        if (piece == null) return Outcome.err("no piece at source");
        Color movingColor = piece.getColor();
        if (movingColor != game.getTurn()) {
            return Outcome.err("not that side's turn");
        }

        Piece target = board.get(to);
        if (target != null && target.getColor() == movingColor) {
            return Outcome.err("can't capture your own piece");
        }
        if (isDuckSquare(game, to)) {
            return Outcome.err("a duck is blocking that square");
        }

        SpecialKind kind = classify(board, piece, from, to, game.getEnPassantTarget());
        if (kind == null) return Outcome.err("illegal move for that piece");
        if (pathBlockedByDuck(game, piece, from, to, kind)) {
            return Outcome.err("a duck is in the way");
        }

        // Detect whether this move would capture something. En passant captures
        // the pawn behind the destination, not the destination itself.
        Position capturedSquare;
        if (kind == SpecialKind.EN_PASSANT) {
            int capturedRank = movingColor == Color.WHITE ? to.rank() - 1 : to.rank() + 1;
            capturedSquare = new Position(to.file(), capturedRank);
        } else {
            capturedSquare = to;
        }
        Piece victim = board.get(capturedSquare);
        boolean isCapture = victim != null;

        CaptureOutcome standoff = isCapture ? rollStandoff() : null;
        if (standoff != null) {
            game.recordEvent(new RandomEvent(
                    RandomEvent.EventKind.CAPTURE_STANDOFF, standoff.label(),
                    CaptureOutcome.labels()));
        }

        // Reset en-passant target before any branch (most moves clear it).
        game.setEnPassantTarget(null);

        if (standoff == CaptureOutcome.NOTHING) {
            // Nothing happens: nobody moves, no promotion, turn consumed.
            game.getHistory().add(new Game.MoveRecord(
                    move, piece.getType(), movingColor, null, null));
            game.log(JournalEntry.JournalKind.STANDOFF, movingColor,
                    Notation.glyph(movingColor, piece.getType()) + " "
                            + Notation.pieceName(piece.getType()) + " " + Notation.square(from)
                            + " attacks " + Notation.glyph(victim.getColor(), victim.getType())
                            + " " + Notation.pieceName(victim.getType())
                            + " at " + Notation.square(capturedSquare)
                            + " — standoff: nothing happens.");
            game.setTurn(movingColor.opposite());
            return Outcome.success();
        }
        if (standoff == CaptureOutcome.GOT_TAKEN) {
            // Attacker dies, defender stays.
            board.set(from, null);
            game.getHistory().add(new Game.MoveRecord(
                    move, piece.getType(), movingColor, null, null));
            game.log(JournalEntry.JournalKind.STANDOFF, movingColor,
                    Notation.glyph(movingColor, piece.getType()) + " "
                            + Notation.pieceName(piece.getType()) + " " + Notation.square(from)
                            + " attacks " + Notation.glyph(victim.getColor(), victim.getType())
                            + " " + Notation.pieceName(victim.getType())
                            + " at " + Notation.square(capturedSquare)
                            + " — standoff: got taken! Attacker lost.");
            if (piece.getType() == PieceType.KING
                    && !hasAnyKing(board, movingColor)) {
                game.setStatus(movingColor == Color.WHITE
                        ? GameStatus.BLACK_WINS : GameStatus.WHITE_WINS);
            } else {
                game.setTurn(movingColor.opposite());
            }
            return Outcome.success();
        }

        // TAKES (or non-capturing move): proceed normally.
        PromotionOutcome promoRoll = (kind == SpecialKind.PROMOTION) ? rollPromotion() : null;
        Piece captured = execute(board, piece, from, to, kind, promoRoll);

        if (kind == SpecialKind.DOUBLE_PAWN) {
            int midRank = (from.rank() + to.rank()) / 2;
            game.setEnPassantTarget(new Position(from.file(), midRank));
        }

        game.getHistory().add(new Game.MoveRecord(
                move, piece.getType(), movingColor,
                captured == null ? null : captured.getType(),
                promoRoll));

        game.log(JournalEntry.JournalKind.MOVE, movingColor,
                describeMove(piece, from, to, kind, captured, capturedSquare, movingColor));

        if (promoRoll != null) {
            game.recordEvent(new RandomEvent(
                    RandomEvent.EventKind.PROMOTION, promoRoll.label(), PROMOTION_LABELS));
            if (promoRoll.failed()) {
                game.log(JournalEntry.JournalKind.PROMOTION, movingColor,
                        "Promotion fails — pawn returns to " + Notation.square(from) + ".");
            } else {
                game.log(JournalEntry.JournalKind.PROMOTION, movingColor,
                        "Promotes to " + promoRoll.label().toLowerCase() + " "
                                + Notation.glyph(movingColor, promoRoll.pieceType()) + "!");
            }
        }

        if (captured != null && captured.getType() == PieceType.KING
                && !hasAnyKing(board, captured.getColor())) {
            game.setStatus(movingColor == Color.WHITE ? GameStatus.WHITE_WINS : GameStatus.BLACK_WINS);
        } else {
            game.setTurn(movingColor.opposite());
        }
        return Outcome.success();
    }

    private String describeMove(Piece piece, Position from, Position to, SpecialKind kind,
                                Piece captured, Position capturedSquare, Color mover) {
        String mg = Notation.glyph(mover, piece.getType());
        String pieceName = Notation.pieceName(piece.getType());
        if (kind == SpecialKind.CASTLE_KINGSIDE) {
            return Notation.side(mover) + " castles kingside " + mg + ".";
        }
        if (kind == SpecialKind.CASTLE_QUEENSIDE) {
            return Notation.side(mover) + " castles queenside " + mg + ".";
        }
        if (kind == SpecialKind.EN_PASSANT && captured != null) {
            return mg + " " + pieceName + " " + Notation.square(from) + " → " + Notation.square(to)
                    + " captures en passant "
                    + Notation.glyph(captured.getColor(), captured.getType())
                    + " at " + Notation.square(capturedSquare) + ".";
        }
        if (captured != null) {
            return mg + " " + pieceName + " " + Notation.square(from) + " → " + Notation.square(to)
                    + " takes "
                    + Notation.glyph(captured.getColor(), captured.getType())
                    + " " + Notation.pieceName(captured.getType()) + ".";
        }
        return mg + " " + pieceName + " " + Notation.square(from) + " → " + Notation.square(to) + ".";
    }

    private boolean isDuckSquare(Game game, Position p) {
        return game.getDucks().stream().anyMatch(d -> d.position().equals(p));
    }

    private boolean pathBlockedByDuck(Game game, Piece piece, Position from, Position to, SpecialKind kind) {
        // Knights jump; sliding pieces follow straight/diagonal paths between from and to.
        if (piece.getType() == PieceType.KNIGHT) return false;
        if (kind == SpecialKind.CASTLE_KINGSIDE || kind == SpecialKind.CASTLE_QUEENSIDE) {
            int step = to.file() > from.file() ? 1 : -1;
            // Rook is whichever piece classifyKing already verified at to.file() + step.
            int rookFile = to.file() + step;
            for (int f = from.file() + step; f != rookFile; f += step) {
                if (isDuckSquare(game, new Position(f, from.rank()))) return true;
            }
            return false;
        }
        int df = to.file() - from.file();
        int dr = to.rank() - from.rank();
        boolean straight = (df == 0) ^ (dr == 0);
        boolean diag = Math.abs(df) == Math.abs(dr) && df != 0;
        if (!straight && !diag) return false; // single-step pieces — only landing matters
        int stepF = Integer.signum(df);
        int stepR = Integer.signum(dr);
        int f = from.file() + stepF;
        int r = from.rank() + stepR;
        while (f != to.file() || r != to.rank()) {
            if (isDuckSquare(game, new Position(f, r))) return true;
            f += stepF;
            r += stepR;
        }
        return false;
    }

    private CaptureOutcome rollStandoff() {
        int total = 0;
        for (CaptureOutcome o : CaptureOutcome.values()) total += o.weight();
        int pick = rng.nextInt(total);
        int acc = 0;
        for (CaptureOutcome o : CaptureOutcome.values()) {
            acc += o.weight();
            if (pick < acc) return o;
        }
        return CaptureOutcome.TAKES;
    }

    public List<Move> legalMovesForColor(Game game, Color color) {
        List<Move> result = new ArrayList<>();
        Board board = game.getBoard();
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = board.get(f, r);
                if (p == null || p.getColor() != color) continue;
                Position from = new Position(f, r);
                appendLegalMoves(game, p, from, result);
            }
        }
        return result;
    }

    public List<Move> legalMovesFromPosition(Game game, Position from) {
        Board board = game.getBoard();
        Piece p = board.get(from);
        if (p == null) return List.of();
        List<Move> result = new ArrayList<>();
        appendLegalMoves(game, p, from, result);
        return result;
    }

    /**
     * Returns every position holding a piece of {@code color} that has at
     * least one legal move from where it stands. Used to pick a forced piece
     * for the FORCED turn action without ever landing on a stuck piece.
     */
    public List<Position> movablePieces(Game game, Color color) {
        List<Position> result = new ArrayList<>();
        Board board = game.getBoard();
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = board.get(f, r);
                if (p == null || p.getColor() != color) continue;
                Position pos = new Position(f, r);
                if (!legalMovesFromPosition(game, pos).isEmpty()) {
                    result.add(pos);
                }
            }
        }
        return result;
    }

    private void appendLegalMoves(Game game, Piece piece, Position from, List<Move> out) {
        Board board = game.getBoard();
        for (int tf = 0; tf < 8; tf++) {
            for (int tr = 0; tr < 8; tr++) {
                if (tf == from.file() && tr == from.rank()) continue;
                Position to = new Position(tf, tr);
                Piece target = board.get(to);
                if (target != null && target.getColor() == piece.getColor()) continue;
                if (isDuckSquare(game, to)) continue;
                SpecialKind kind = classify(board, piece, from, to, game.getEnPassantTarget());
                if (kind == null) continue;
                if (pathBlockedByDuck(game, piece, from, to, kind)) continue;
                out.add(new Move(from, to));
            }
        }
    }

    private boolean hasAnyKing(Board board, Color color) {
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = board.get(f, r);
                if (p != null && p.getType() == PieceType.KING && p.getColor() == color) {
                    return true;
                }
            }
        }
        return false;
    }

    private PromotionOutcome rollPromotion() {
        PromotionOutcome[] all = PromotionOutcome.values();
        return all[rng.nextInt(all.length)];
    }

    private SpecialKind classify(Board board, Piece piece, Position from, Position to, Position epTarget) {
        return switch (piece.getType()) {
            case PAWN -> classifyPawn(board, piece, from, to, epTarget);
            case KNIGHT -> classifyKnight(from, to) ? SpecialKind.NORMAL : null;
            case BISHOP -> classifySliding(board, from, to, true, false) ? SpecialKind.NORMAL : null;
            case ROOK -> classifySliding(board, from, to, false, true) ? SpecialKind.NORMAL : null;
            case QUEEN -> classifySliding(board, from, to, true, true) ? SpecialKind.NORMAL : null;
            case KING -> classifyKing(board, piece, from, to);
        };
    }

    private SpecialKind classifyPawn(Board board, Piece piece, Position from, Position to, Position epTarget) {
        int dir = piece.getColor() == Color.WHITE ? 1 : -1;
        int startRank = piece.getColor() == Color.WHITE ? 1 : 6;
        int promoRank = piece.getColor() == Color.WHITE ? 7 : 0;
        int df = to.file() - from.file();
        int dr = to.rank() - from.rank();

        if (df == 0 && dr == dir && board.get(to) == null) {
            return to.rank() == promoRank ? SpecialKind.PROMOTION : SpecialKind.NORMAL;
        }
        if (df == 0 && dr == 2 * dir && from.rank() == startRank
                && board.get(to) == null
                && board.get(from.file(), from.rank() + dir) == null) {
            return SpecialKind.DOUBLE_PAWN;
        }
        if (Math.abs(df) == 1 && dr == dir) {
            Piece victim = board.get(to);
            if (victim != null && victim.getColor() != piece.getColor()) {
                return to.rank() == promoRank ? SpecialKind.PROMOTION : SpecialKind.NORMAL;
            }
            if (victim == null && epTarget != null && epTarget.equals(to)) {
                return SpecialKind.EN_PASSANT;
            }
        }
        return null;
    }

    private boolean classifyKnight(Position from, Position to) {
        int df = Math.abs(to.file() - from.file());
        int dr = Math.abs(to.rank() - from.rank());
        return (df == 1 && dr == 2) || (df == 2 && dr == 1);
    }

    private boolean classifySliding(Board board, Position from, Position to, boolean diagonal, boolean straight) {
        int df = to.file() - from.file();
        int dr = to.rank() - from.rank();
        boolean isDiag = Math.abs(df) == Math.abs(dr) && df != 0;
        boolean isStraight = (df == 0) ^ (dr == 0);
        if (isDiag && !diagonal) return false;
        if (isStraight && !straight) return false;
        if (!isDiag && !isStraight) return false;
        int stepF = Integer.signum(df);
        int stepR = Integer.signum(dr);
        int f = from.file() + stepF;
        int r = from.rank() + stepR;
        while (f != to.file() || r != to.rank()) {
            if (board.get(f, r) != null) return false;
            f += stepF;
            r += stepR;
        }
        return true;
    }

    private SpecialKind classifyKing(Board board, Piece king, Position from, Position to) {
        int df = to.file() - from.file();
        int dr = to.rank() - from.rank();
        if (Math.abs(df) <= 1 && Math.abs(dr) <= 1) return SpecialKind.NORMAL;
        // Castling: king and rook on the same rank, both unmoved, all squares between
        // them empty. King ends up adjacent to the rook on its original side; the rook
        // hops to the opposite side of the king (handled in execute).
        if (king.hasMoved() || dr != 0 || Math.abs(df) < 2) return null;
        int dir = Integer.signum(df);
        int rookFile = findCastlingRook(board, king.getColor(), from, dir);
        if (rookFile < 0) return null;
        // King's destination must be the square adjacent to the rook on the king's side.
        if (to.file() != rookFile - dir) return null;
        return dir > 0 ? SpecialKind.CASTLE_KINGSIDE : SpecialKind.CASTLE_QUEENSIDE;
    }

    private int findCastlingRook(Board board, Color color, Position kingFrom, int dir) {
        for (int f = kingFrom.file() + dir; f >= 0 && f < 8; f += dir) {
            Piece p = board.get(f, kingFrom.rank());
            if (p == null) continue;
            if (p.getType() == PieceType.ROOK && p.getColor() == color && !p.hasMoved()) {
                return f;
            }
            return -1; // first non-empty square isn't a valid rook
        }
        return -1;
    }

    private Piece execute(Board board, Piece piece, Position from, Position to,
                          SpecialKind kind, PromotionOutcome promoRoll) {
        Piece captured;
        if (kind == SpecialKind.EN_PASSANT) {
            int capturedRank = piece.getColor() == Color.WHITE ? to.rank() - 1 : to.rank() + 1;
            Position cap = new Position(to.file(), capturedRank);
            captured = board.get(cap);
            board.set(cap, null);
        } else {
            captured = board.get(to);
        }
        board.set(from, null);

        if (kind == SpecialKind.PROMOTION) {
            board.set(to, null);
            if (promoRoll.failed()) {
                // Pawn returns to source. Captured piece (if any) stays captured.
                board.set(from, piece);
            } else {
                Piece promoted = new Piece(promoRoll.pieceType(), piece.getColor());
                promoted.markMoved();
                board.set(to, promoted);
            }
        } else {
            board.set(to, piece);
            piece.markMoved();
        }

        if (kind == SpecialKind.CASTLE_KINGSIDE || kind == SpecialKind.CASTLE_QUEENSIDE) {
            int dir = kind == SpecialKind.CASTLE_KINGSIDE ? 1 : -1;
            // After classify+king-move: king sits at `to`; rook is at to.file() + dir.
            int rookFile = to.file() + dir;
            Piece rook = board.get(rookFile, from.rank());
            board.set(new Position(rookFile, from.rank()), null);
            board.set(new Position(to.file() - dir, from.rank()), rook);
            if (rook != null) rook.markMoved();
        }
        return captured;
    }
}
