package com.horriblechess.model;

import java.util.List;

public record RandomEvent(EventKind kind, String outcome, List<String> possibleOutcomes, Color subject) {
    /** The side this event concerns (e.g. whose turn the action applies to),
     *  captured when the event is recorded — before any turn flip — so the
     *  client can label it correctly even though later broadcasts may have
     *  already advanced the turn. Null when not applicable. */
    public RandomEvent(EventKind kind, String outcome, List<String> possibleOutcomes) {
        this(kind, outcome, possibleOutcomes, null);
    }

    public enum EventKind {
        FIRST_MOVER,
        PROMOTION,
        CAPTURE_STANDOFF,
        TURN_ACTION,
        PIECE_SELECTION,
        SQUARE_EVENT
    }
}
