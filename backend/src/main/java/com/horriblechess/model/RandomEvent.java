package com.horriblechess.model;

import java.util.List;

public record RandomEvent(EventKind kind, String outcome, List<String> possibleOutcomes,
                          Color subject, Duel duel) {
    /** The side this event concerns (e.g. whose turn the action applies to),
     *  captured when the event is recorded — before any turn flip — so the
     *  client can label it correctly even though later broadcasts may have
     *  already advanced the turn. Null when not applicable. */
    public RandomEvent(EventKind kind, String outcome, List<String> possibleOutcomes) {
        this(kind, outcome, possibleOutcomes, null, null);
    }

    public RandomEvent(EventKind kind, String outcome, List<String> possibleOutcomes, Color subject) {
        this(kind, outcome, possibleOutcomes, subject, null);
    }

    /** Attacker vs defender details for a CAPTURE_STANDOFF, so the client can
     *  render the duel between the two specific pieces. Null for other events. */
    public record Duel(String attackerPiece, Color attackerColor,
                       String defenderPiece, Color defenderColor) {}

    public enum EventKind {
        FIRST_MOVER,
        PROMOTION,
        CAPTURE_STANDOFF,
        TURN_ACTION,
        PIECE_SELECTION,
        SQUARE_EVENT
    }
}
