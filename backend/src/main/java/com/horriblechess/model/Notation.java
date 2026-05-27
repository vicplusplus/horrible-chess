package com.horriblechess.model;

public final class Notation {
    private static final String[] FILES = {"a", "b", "c", "d", "e", "f", "g", "h"};

    private Notation() {}

    public static String square(Position p) {
        return FILES[p.file()] + (p.rank() + 1);
    }

    public static String pieceName(PieceType t) {
        return switch (t) {
            case PAWN -> "pawn";
            case KNIGHT -> "knight";
            case BISHOP -> "bishop";
            case ROOK -> "rook";
            case QUEEN -> "queen";
            case KING -> "king";
        };
    }

    public static String side(Color c) {
        return c == Color.WHITE ? "White" : "Black";
    }

    public static String sideLower(Color c) {
        return c == Color.WHITE ? "white" : "black";
    }

    public static String sidePossessive(Color c) {
        return c == Color.WHITE ? "white's" : "black's";
    }

    public static String glyph(Color c, PieceType t) {
        if (c == Color.WHITE) {
            return switch (t) {
                case PAWN -> "♙";   // ♙
                case KNIGHT -> "♘"; // ♘
                case BISHOP -> "♗"; // ♗
                case ROOK -> "♖";   // ♖
                case QUEEN -> "♕";  // ♕
                case KING -> "♔";   // ♔
            };
        }
        return switch (t) {
            case PAWN -> "♟";   // ♟
            case KNIGHT -> "♞"; // ♞
            case BISHOP -> "♝"; // ♝
            case ROOK -> "♜";   // ♜
            case QUEEN -> "♛";  // ♛
            case KING -> "♚";   // ♚
        };
    }
}
