package com.horriblechess.model;

public record Duck(Position position, int turnsRemaining) {
    public Duck withTurnsRemaining(int n) {
        return new Duck(position, n);
    }
}
