package com.horriblechess.model;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public enum TurnAction {
    // Relative weights for the per-turn roll. AUTO is deliberately rare (~10%);
    // the other four split the rest evenly (~22.5% each).
    NORMAL("Move", 9),
    DOUBLE("Double Turn", 9),
    SKIP("Turn Skipped", 9),
    FORCED("Forced Piece", 9),
    AUTO("Auto Move", 4);

    private final String label;
    private final int weight;

    TurnAction(String label, int weight) {
        this.label = label;
        this.weight = weight;
    }

    public String label() {
        return label;
    }

    public static List<String> labels() {
        return Arrays.stream(values()).map(TurnAction::label).toList();
    }

    public static TurnAction randomWeighted(Random rng) {
        int total = 0;
        for (TurnAction a : values()) total += a.weight;
        int roll = rng.nextInt(total);
        for (TurnAction a : values()) {
            roll -= a.weight;
            if (roll < 0) return a;
        }
        return NORMAL; // unreachable
    }
}
