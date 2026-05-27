package com.horriblechess.model;

public record JournalEntry(JournalKind kind, Color color, String text) {
    public enum JournalKind {
        GAME,         // first mover, game over
        TURN,         // turn action rolled
        MOVE,         // a piece moved (player or auto)
        STANDOFF,     // capture roll resolved
        PROMOTION,    // promotion roll resolved
        SQUARE_EVENT  // ? square triggered
    }
}
