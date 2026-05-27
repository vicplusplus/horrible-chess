package com.horriblechess;

import com.horriblechess.model.Color;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.Move;
import com.horriblechess.model.Piece;
import com.horriblechess.model.PieceType;
import com.horriblechess.model.CaptureOutcome;
import com.horriblechess.model.Position;
import com.horriblechess.model.PromotionOutcome;
import com.horriblechess.model.RandomEvent;
import com.horriblechess.model.TurnAction;
import com.horriblechess.service.MoveExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveExecutorTest {
    // Default RNG returns 0 for every roll: standoff → TAKES, promotion → KNIGHT.
    // Lets us test the standard flow without flakiness from the new capture roll.
    private final MoveExecutor exec = new MoveExecutor(new Random() {
        @Override public int nextInt(int bound) { return 0; }
    });

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
    void castlingWorksFromNonStandardKingFile() {
        // King on c1 with rook on h1 — random-back-row scenario. The rule lets the
        // king move to g1 (adjacent to the rook) and the rook hops to f1.
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        g.getBoard().set(p(2, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(7, 0), new Piece(PieceType.ROOK, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));

        MoveExecutor.Outcome out = exec.apply(g, new Move(p(2, 0), p(6, 0)), w);
        assertTrue(out.ok(), out.error());
        assertEquals(PieceType.KING, g.getBoard().get(p(6, 0)).getType());
        assertEquals(PieceType.ROOK, g.getBoard().get(p(5, 0)).getType());
        assertNull(g.getBoard().get(p(2, 0)));
        assertNull(g.getBoard().get(p(7, 0)));
    }

    @Test
    void castlingRejectedIfPathBlocked() {
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        g.getBoard().set(p(2, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(7, 0), new Piece(PieceType.ROOK, Color.WHITE));
        g.getBoard().set(p(4, 0), new Piece(PieceType.KNIGHT, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));

        MoveExecutor.Outcome out = exec.apply(g, new Move(p(2, 0), p(6, 0)), w);
        assertFalse(out.ok());
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
    void promotionPicksOutcomeViaServerRoll() {
        // Force the RNG to land on KNIGHT (index 0).
        MoveExecutor execFixed = new MoveExecutor(new Random() {
            @Override public int nextInt(int bound) { return 0; }
        });
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        g.getBoard().set(p(0, 1), null);
        g.getBoard().set(p(0, 6), new Piece(PieceType.PAWN, Color.WHITE));
        g.getBoard().set(p(0, 7), null);

        MoveExecutor.Outcome out = execFixed.apply(g, new Move(p(0, 6), p(0, 7)), w);
        assertTrue(out.ok(), out.error());
        assertEquals(PieceType.KNIGHT, g.getBoard().get(p(0, 7)).getType());
        RandomEvent ev = g.getLastEvent();
        assertNotNull(ev);
        assertEquals(RandomEvent.EventKind.PROMOTION, ev.kind());
        assertEquals("Knight", ev.outcome());
    }

    @Test
    void promotionFailedReturnsPawnButKeepsCapture() {
        int failedIdx = PromotionOutcome.FAILED.ordinal();
        MoveExecutor execFailed = new MoveExecutor(new Random() {
            @Override public int nextInt(int bound) { return failedIdx; }
        });
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        // White pawn at a7 with a black rook on b8 to capture.
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        g.getBoard().set(p(4, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));
        g.getBoard().set(p(0, 6), new Piece(PieceType.PAWN, Color.WHITE));
        g.getBoard().set(p(1, 7), new Piece(PieceType.ROOK, Color.BLACK));

        MoveExecutor.Outcome out = execFailed.apply(g, new Move(p(0, 6), p(1, 7)), w);
        assertTrue(out.ok(), out.error());
        // Pawn returned to source.
        Piece pawn = g.getBoard().get(p(0, 6));
        assertNotNull(pawn);
        assertEquals(PieceType.PAWN, pawn.getType());
        // Destination is empty (captured rook gone, no promoted piece).
        assertNull(g.getBoard().get(p(1, 7)));
        // Turn consumed.
        assertEquals(Color.BLACK, g.getTurn());
        assertEquals("Failed", g.getLastEvent().outcome());
    }

    @Test
    void forcedPieceRejectsOtherPieces() {
        Players pl = freshGame();
        pl.game().setCurrentTurnAction(TurnAction.FORCED);
        pl.game().setForcedPiecePosition(p(4, 1)); // e2 pawn
        // Try moving d2 pawn — must be rejected.
        MoveExecutor.Outcome bad = exec.apply(pl.game(), new Move(p(3, 1), p(3, 3)), pl.white());
        assertFalse(bad.ok());
        // Move the e2 pawn — must succeed.
        MoveExecutor.Outcome ok = exec.apply(pl.game(), new Move(p(4, 1), p(4, 3)), pl.white());
        assertTrue(ok.ok(), ok.error());
    }

    @Test
    void skipBlocksAnyMove() {
        Players pl = freshGame();
        pl.game().setCurrentTurnAction(TurnAction.SKIP);
        MoveExecutor.Outcome out = exec.apply(pl.game(), new Move(p(4, 1), p(4, 3)), pl.white());
        assertFalse(out.ok());
    }

    @Test
    void captureStandoffNothingLeavesBothPiecesInPlace() {
        // Standoff bucket: 70 falls inside NOTHING (60..84). Force it.
        MoveExecutor exec70 = new MoveExecutor(new Random() {
            @Override public int nextInt(int bound) { return 70; }
        });
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        g.getBoard().set(p(4, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));
        Piece attacker = new Piece(PieceType.ROOK, Color.WHITE);
        Piece defender = new Piece(PieceType.KNIGHT, Color.BLACK);
        g.getBoard().set(p(0, 0), attacker);
        g.getBoard().set(p(0, 3), defender);

        MoveExecutor.Outcome out = exec70.apply(g, new Move(p(0, 0), p(0, 3)), w);
        assertTrue(out.ok(), out.error());
        // Both pieces still at their squares.
        assertEquals(attacker, g.getBoard().get(p(0, 0)));
        assertEquals(defender, g.getBoard().get(p(0, 3)));
        // Turn flipped.
        assertEquals(Color.BLACK, g.getTurn());
        // Event recorded.
        assertEquals(CaptureOutcome.NOTHING.label(), g.getLastEvent().outcome());
    }

    @Test
    void captureStandoffGotTakenRemovesAttacker() {
        // 90 falls inside GOT_TAKEN (85..99).
        MoveExecutor exec90 = new MoveExecutor(new Random() {
            @Override public int nextInt(int bound) { return 90; }
        });
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        g.getBoard().set(p(4, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));
        g.getBoard().set(p(0, 0), new Piece(PieceType.ROOK, Color.WHITE));
        g.getBoard().set(p(0, 3), new Piece(PieceType.KNIGHT, Color.BLACK));

        MoveExecutor.Outcome out = exec90.apply(g, new Move(p(0, 0), p(0, 3)), w);
        assertTrue(out.ok(), out.error());
        // Attacker gone, defender unchanged.
        assertNull(g.getBoard().get(p(0, 0)));
        assertEquals(PieceType.KNIGHT, g.getBoard().get(p(0, 3)).getType());
        assertEquals(Color.BLACK, g.getTurn());
    }

    @Test
    void gotTakenOfLastKingEndsGame() {
        MoveExecutor exec90 = new MoveExecutor(new Random() {
            @Override public int nextInt(int bound) { return 90; }
        });
        Game g = new Game("test");
        String w = g.addPlayer();
        g.addPlayer();
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        // White's only king is the attacker.
        g.getBoard().set(p(4, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));
        g.getBoard().set(p(5, 1), new Piece(PieceType.ROOK, Color.BLACK)); // defender adjacent

        // King at e1 tries to take rook at f2 → GOT_TAKEN.
        MoveExecutor.Outcome out = exec90.apply(g, new Move(p(4, 0), p(5, 1)), w);
        assertTrue(out.ok(), out.error());
        assertEquals(GameStatus.BLACK_WINS, g.getStatus());
    }

    @Test
    void legalMovesForColorAtStart() {
        Players pl = freshGame();
        List<Move> white = exec.legalMovesForColor(pl.game(), Color.WHITE);
        // Each of 8 pawns has 2 forward moves; each of 2 knights has 2 jumps. 8*2 + 2*2 = 20.
        assertEquals(20, white.size());
    }

    @Test
    void promotionToKingGivesExtraLife() {
        int kingIdx = PromotionOutcome.KING.ordinal();
        MoveExecutor execKing = new MoveExecutor(new Random() {
            @Override public int nextInt(int bound) { return kingIdx; }
        });
        Game g = new Game("test");
        String w = g.addPlayer();
        String b = g.addPlayer();
        for (int f = 0; f < 8; f++) for (int r = 0; r < 8; r++) g.getBoard().set(p(f, r), null);
        g.getBoard().set(p(4, 0), new Piece(PieceType.KING, Color.WHITE));
        g.getBoard().set(p(4, 7), new Piece(PieceType.KING, Color.BLACK));
        g.getBoard().set(p(0, 6), new Piece(PieceType.PAWN, Color.WHITE));
        g.getBoard().set(p(1, 7), new Piece(PieceType.ROOK, Color.BLACK));

        // White promotes a7-a8 → KING.
        assertTrue(execKing.apply(g, new Move(p(0, 6), p(0, 7)), w).ok());
        // Black captures the promoted king — white still has the original on e1, so game continues.
        MoveExecutor.Outcome cap = execKing.apply(g, new Move(p(1, 7), p(0, 7)), b);
        assertTrue(cap.ok(), cap.error());
        assertEquals(GameStatus.IN_PROGRESS, g.getStatus());
        // White now captures black's only king with their original king (adjacent move).
        // Black king is on e8; need a white piece adjacent. Move white king e1→e2 then it's too slow.
        // Instead, place a white queen ready to capture the black king to finish the assertion.
        g.getBoard().set(p(4, 6), new Piece(PieceType.QUEEN, Color.WHITE));
        MoveExecutor.Outcome kill = execKing.apply(g, new Move(p(4, 6), p(4, 7)), w);
        assertTrue(kill.ok(), kill.error());
        assertEquals(GameStatus.WHITE_WINS, g.getStatus());
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
