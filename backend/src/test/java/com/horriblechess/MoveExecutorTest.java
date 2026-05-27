package com.horriblechess;

import com.horriblechess.model.Color;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.Move;
import com.horriblechess.model.Piece;
import com.horriblechess.model.PieceType;
import com.horriblechess.model.Position;
import com.horriblechess.service.MoveExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveExecutorTest {
    private final MoveExecutor exec = new MoveExecutor();

    private record Players(Game game, String white, String black) {}

    private Players freshGame() {
        Game g = new Game("test");
        String w = g.addPlayer();
        String b = g.addPlayer();
        return new Players(g, w, b);
    }

    private Position p(int f, int r) { return new Position(f, r); }

    @Test
    void pawnDoublePushSetsEnPassantTarget() {
        Players pl = freshGame();
        MoveExecutor.Outcome out = exec.apply(pl.game(), new Move(p(4, 1), p(4, 3)), pl.white());
        assertTrue(out.ok(), out.error());
        assertEquals(new Position(4, 2), pl.game().getEnPassantTarget());
        assertEquals(Color.BLACK, pl.game().getTurn());
    }

    @Test
    void enPassantCaptureWorks() {
        Players pl = freshGame();
        // White: e2-e4
        exec.apply(pl.game(), new Move(p(4, 1), p(4, 3)), pl.white());
        // Black: a7-a6 (filler)
        exec.apply(pl.game(), new Move(p(0, 6), p(0, 5)), pl.black());
        // White: e4-e5
        exec.apply(pl.game(), new Move(p(4, 3), p(4, 4)), pl.white());
        // Black: d7-d5 (en passant target now d6)
        exec.apply(pl.game(), new Move(p(3, 6), p(3, 4)), pl.black());
        assertEquals(new Position(3, 5), pl.game().getEnPassantTarget());
        // White: e5xd6 en passant
        MoveExecutor.Outcome out = exec.apply(pl.game(), new Move(p(4, 4), p(3, 5)), pl.white());
        assertTrue(out.ok(), out.error());
        assertNull(pl.game().getBoard().get(p(3, 4)));
        assertNotNull(pl.game().getBoard().get(p(3, 5)));
    }

    @Test
    void castlingKingsideWorks() {
        Players pl = freshGame();
        // Move pieces between king and rook off
        // g1 knight -> f3
        exec.apply(pl.game(), new Move(p(6, 0), p(5, 2)), pl.white());
        exec.apply(pl.game(), new Move(p(0, 6), p(0, 5)), pl.black()); // a7-a6
        // e2-e3 to open bishop
        exec.apply(pl.game(), new Move(p(4, 1), p(4, 2)), pl.white());
        exec.apply(pl.game(), new Move(p(0, 5), p(0, 4)), pl.black());
        // f1 bishop -> e2
        exec.apply(pl.game(), new Move(p(5, 0), p(4, 1)), pl.white());
        exec.apply(pl.game(), new Move(p(0, 4), p(0, 3)), pl.black());
        // Now castle kingside: e1 -> g1
        MoveExecutor.Outcome out = exec.apply(pl.game(), new Move(p(4, 0), p(6, 0)), pl.white());
        assertTrue(out.ok(), out.error());
        Piece king = pl.game().getBoard().get(p(6, 0));
        Piece rook = pl.game().getBoard().get(p(5, 0));
        assertNotNull(king);
        assertNotNull(rook);
        assertEquals(PieceType.KING, king.getType());
        assertEquals(PieceType.ROOK, rook.getType());
        assertNull(pl.game().getBoard().get(p(4, 0)));
        assertNull(pl.game().getBoard().get(p(7, 0)));
    }

    @Test
    void cannotMoveOpponentPiece() {
        Players pl = freshGame();
        MoveExecutor.Outcome out = exec.apply(pl.game(), new Move(p(4, 6), p(4, 5)), pl.white());
        assertFalse(out.ok());
    }

    @Test
    void cannotMoveOutOfTurn() {
        Players pl = freshGame();
        MoveExecutor.Outcome out = exec.apply(pl.game(), new Move(p(4, 6), p(4, 5)), pl.black());
        assertFalse(out.ok());
    }

    @Test
    void cannotCaptureOwnPiece() {
        Players pl = freshGame();
        MoveExecutor.Outcome out = exec.apply(pl.game(), new Move(p(0, 0), p(0, 1)), pl.white());
        assertFalse(out.ok());
    }

    @Test
    void promotionRequiresPromotionType() {
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        // Place a white pawn on rank 7 (file 0)
        g.getBoard().set(p(0, 1), null);
        g.getBoard().set(p(0, 6), new Piece(PieceType.PAWN, Color.WHITE));
        g.getBoard().set(p(0, 7), null); // remove black rook
        MoveExecutor.Outcome missing = exec.apply(g, new Move(p(0, 6), p(0, 7)), w);
        assertFalse(missing.ok());
        MoveExecutor.Outcome ok = exec.apply(g, new Move(p(0, 6), p(0, 7), PieceType.QUEEN), w);
        assertTrue(ok.ok(), ok.error());
        assertEquals(PieceType.QUEEN, g.getBoard().get(p(0, 7)).getType());
    }

    @Test
    void capturingKingEndsGame() {
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        // Set up so white can capture black king immediately. Put white queen next to black king.
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        g.getBoard().set(p(4, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));
        g.getBoard().set(p(4, 6), new Piece(PieceType.QUEEN, Color.WHITE));
        MoveExecutor.Outcome out = exec.apply(g, new Move(p(4, 6), p(4, 7)), w);
        assertTrue(out.ok(), out.error());
        assertEquals(GameStatus.WHITE_WINS, g.getStatus());
    }
}
