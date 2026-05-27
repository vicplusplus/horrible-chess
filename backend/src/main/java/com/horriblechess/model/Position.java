package com.horriblechess.model;

public record Position(int file, int rank) {
    public boolean onBoard() {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    public static Position of(int file, int rank) {
        return new Position(file, rank);
    }
}
