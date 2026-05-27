package com.horriblechess.service;

import com.horriblechess.model.Board;
import com.horriblechess.model.Color;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.Move;
import com.horriblechess.model.Piece;
import com.horriblechess.model.PieceType;
import com.horriblechess.model.Position;
import com.horriblechess.model.PromotionOutcome;
import com.horriblechess.model.RandomEvent;
import org.springframework.stereotype.Component;

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

        Position from = move.from();
        Position to = move.to();
        if (from == null || to == null || !from.onBoard() || !to.onBoard()) {
            return Outcome.err("position off board");
        }
        if (from.equals(to)) return Outcome.err("must move to a different square");

        Board board = game.getBoard();
        Piece piece = board.get(from);
        if (piece == null) return Outcome.err("no piece at source");
        if (piece.getColor() != playerColor) return Outcome.err("that piece is not yours");

        Piece target = board.get(to);
        if (target != null && target.getColor() == playerColor) {
            return Outcome.err("can't capture your own piece");
        }

        SpecialKind kind = classify(board, piece, from, to, game.getEnPassantTarget());
        if (kind == null) return Outcome.err("illegal move for that piece");

        PromotionOutcome promoRoll = (kind == SpecialKind.PROMOTION) ? rollPromotion() : null;
        Piece captured = execute(board, piece, from, to, kind, promoRoll);

        if (kind == SpecialKind.DOUBLE_PAWN) {
            int midRank = (from.rank() + to.rank()) / 2;
            game.setEnPassantTarget(new Position(from.file(), midRank));
        } else {
            game.setEnPassantTarget(null);
        }

        game.getHistory().add(new Game.MoveRecord(
                move, piece.getType(), playerColor,
                captured == null ? null : captured.getType(),
                promoRoll));

        if (promoRoll != null) {
            game.recordEvent(new RandomEvent(
                    RandomEvent.EventKind.PROMOTION, promoRoll.label(), PROMOTION_LABELS));
        }

        if (captured != null && captured.getType() == PieceType.KING
                && !hasAnyKing(board, captured.getColor())) {
            game.setStatus(playerColor == Color.WHITE ? GameStatus.WHITE_WINS : GameStatus.BLACK_WINS);
        } else {
            game.setTurn(playerColor.opposite());
        }
        return Outcome.success();
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
        if (king.hasMoved() || dr != 0 || Math.abs(df) != 2) return null;
        int rookFile = df > 0 ? 7 : 0;
        Piece rook = board.get(rookFile, from.rank());
        if (rook == null || rook.getType() != PieceType.ROOK
                || rook.getColor() != king.getColor() || rook.hasMoved()) return null;
        int step = Integer.signum(df);
        for (int f = from.file() + step; f != rookFile; f += step) {
            if (board.get(f, from.rank()) != null) return null;
        }
        return df > 0 ? SpecialKind.CASTLE_KINGSIDE : SpecialKind.CASTLE_QUEENSIDE;
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

        if (kind == SpecialKind.CASTLE_KINGSIDE) {
            Piece rook = board.get(7, from.rank());
            board.set(new Position(7, from.rank()), null);
            board.set(new Position(5, from.rank()), rook);
            if (rook != null) rook.markMoved();
        } else if (kind == SpecialKind.CASTLE_QUEENSIDE) {
            Piece rook = board.get(0, from.rank());
            board.set(new Position(0, from.rank()), null);
            board.set(new Position(3, from.rank()), rook);
            if (rook != null) rook.markMoved();
        }
        return captured;
    }
}
