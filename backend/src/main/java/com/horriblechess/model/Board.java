package com.horriblechess.model;

import java.util.Random;

public final class Board {
    private final Piece[][] squares = new Piece[8][8];

    private static final PieceType[] BACK_ROW_OPTIONS = {
            PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK,
            PieceType.QUEEN, PieceType.KING
    };

    public static Board startingPosition() {
        Board b = new Board();
        PieceType[] backRow = {
                PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
        };
        fillFromBackRows(b, backRow, backRow);
        return b;
    }

    public static Board randomBackRowPosition(Random rng) {
        Board b = new Board();
        fillFromBackRows(b, rollBackRow(rng), rollBackRow(rng));
        return b;
    }

    private static PieceType[] rollBackRow(Random rng) {
        PieceType[] row = new PieceType[8];
        boolean hasKing = false;
        for (int f = 0; f < 8; f++) {
            row[f] = BACK_ROW_OPTIONS[rng.nextInt(BACK_ROW_OPTIONS.length)];
            if (row[f] == PieceType.KING) hasKing = true;
        }
        if (!hasKing) {
            row[rng.nextInt(8)] = PieceType.KING;
        }
        return row;
    }

    private static void fillFromBackRows(Board b, PieceType[] whiteRow, PieceType[] blackRow) {
        for (int f = 0; f < 8; f++) {
            b.squares[f][0] = new Piece(whiteRow[f], Color.WHITE);
            b.squares[f][1] = new Piece(PieceType.PAWN, Color.WHITE);
            b.squares[f][6] = new Piece(PieceType.PAWN, Color.BLACK);
            b.squares[f][7] = new Piece(blackRow[f], Color.BLACK);
        }
    }

    public Piece get(Position p) {
        if (!p.onBoard()) return null;
        return squares[p.file()][p.rank()];
    }

    public Piece get(int file, int rank) {
        return squares[file][rank];
    }

    public void set(Position p, Piece piece) {
        squares[p.file()][p.rank()] = piece;
    }

    public Position findKing(Color color) {
        for (int f = 0; f < 8; f++) {
            for (int r = 0; r < 8; r++) {
                Piece p = squares[f][r];
                if (p != null && p.getType() == PieceType.KING && p.getColor() == color) {
                    return new Position(f, r);
                }
            }
        }
        return null;
    }
}
