package com.horriblechess.model;

import java.util.Arrays;
import java.util.List;

public enum SquareEvent {
    RANDOM_CAPTURE("Random capture"),
    PIECE_SPAWN("Piece spawn"),
    SKIP_TURN("Skip a turn"),
    COLOR_SWAP("Color swap"),
    RANDOM_MOVE("Random move"),
    EXPLOSION("Explosion"),
    DUCK("Duck");

    private final String label;

    SquareEvent(String label) {
        this.label = label;
    }

    public String label() { return label; }

    public static List<String> labels() {
        return Arrays.stream(values()).map(SquareEvent::label).toList();
    }
}
