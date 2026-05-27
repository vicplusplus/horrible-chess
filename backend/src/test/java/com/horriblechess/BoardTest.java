package com.horriblechess;

import com.horriblechess.model.Board;
import com.horriblechess.model.Color;
import com.horriblechess.model.Piece;
import com.horriblechess.model.PieceType;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardTest {

    @Test
    void randomBackRowHasPawnsInFrontAndAtLeastOneKingEachSide() {
        Board b = Board.randomBackRowPosition(new Random(42));
        for (int f = 0; f < 8; f++) {
            Piece whitePawn = b.get(f, 1);
            Piece blackPawn = b.get(f, 6);
            assertNotNull(whitePawn);
            assertNotNull(blackPawn);
            assertEquals(PieceType.PAWN, whitePawn.getType());
            assertEquals(PieceType.PAWN, blackPawn.getType());
        }
        assertTrue(countKings(b, Color.WHITE) >= 1, "white must have a king");
        assertTrue(countKings(b, Color.BLACK) >= 1, "black must have a king");
    }

    @Test
    void noPawnsLandOnTheBackRow() {
        Board b = Board.randomBackRowPosition(new Random(7));
        for (int f = 0; f < 8; f++) {
            assertNotEquals(PieceType.PAWN, b.get(f, 0).getType());
            assertNotEquals(PieceType.PAWN, b.get(f, 7).getType());
        }
    }

    @Test
    void noKingForcesInjectionOnEmptyRowRoll() {
        // Seed chosen by trying a few until rollBackRow's first pass produces no king.
        // With 5 options and 8 slots, ~17% of rolls have zero kings; the injection
        // path is exercised reliably by seed=3.
        Board b = Board.randomBackRowPosition(new Random(3));
        assertTrue(countKings(b, Color.WHITE) >= 1);
        assertTrue(countKings(b, Color.BLACK) >= 1);
    }

    private int countKings(Board b, Color color) {
        int n = 0;
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = b.get(f, r);
                if (p != null && p.getType() == PieceType.KING && p.getColor() == color) n++;
            }
        }
        return n;
    }
}
