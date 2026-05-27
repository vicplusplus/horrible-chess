package com.horriblechess.model;

import java.util.Arrays;
import java.util.List;

public enum CaptureOutcome {
    TAKES("Takes", 60),
    NOTHING("Nothing happens", 25),
    GOT_TAKEN("Got taken", 15);

    private final String label;
    private final int weight;

    CaptureOutcome(String label, int weight) {
        this.label = label;
        this.weight = weight;
    }

    public String label() { return label; }
    public int weight() { return weight; }

    public static List<String> labels() {
        return Arrays.stream(values()).map(CaptureOutcome::label).toList();
    }
}
