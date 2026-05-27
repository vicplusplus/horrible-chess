package com.horriblechess.model;

import java.util.List;

public record RandomEvent(EventKind kind, String outcome, List<String> possibleOutcomes) {
    public enum EventKind {
        FIRST_MOVER,
        PROMOTION,
        CAPTURE_STANDOFF,
        TURN_ACTION,
        PIECE_SELECTION,
        SQUARE_EVENT
    }
}
