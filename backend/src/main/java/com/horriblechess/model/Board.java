package com.horriblechess.model;

public final class Board {
    private final Piece[][] squares = new Piece[8][8];

    public static Board startingPosition() {
        Board b = new Board();
        PieceType[] backRow = {
                PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
        };
        for (int f = 0; f < 8; f++) {
            b.squares[f][0] = new Piece(backRow[f], Color.WHITE);
            b.squares[f][1] = new Piece(PieceType.PAWN, Color.WHITE);
            b.squares[f][6] = new Piece(PieceType.PAWN, Color.BLACK);
            b.squares[f][7] = new Piece(backRow[f], Color.BLACK);
        }
        return b;
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
