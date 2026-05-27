package com.horriblechess.model;

import java.util.Arrays;
import java.util.List;

public enum TurnAction {
    NORMAL("Move"),
    DOUBLE("Double Turn"),
    SKIP("Turn Skipped"),
    FORCED("Forced Piece"),
    AUTO("Auto Move");

    private final String label;

    TurnAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<String> labels() {
        return Arrays.stream(values()).map(TurnAction::label).toList();
    }
}
