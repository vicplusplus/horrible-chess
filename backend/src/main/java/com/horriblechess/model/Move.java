package com.horriblechess.model;

public record Move(Position from, Position to, PieceType promotion) {
    public Move(Position from, Position to) {
        this(from, to, null);
    }
}
