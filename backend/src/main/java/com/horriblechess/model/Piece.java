package com.horriblechess.model;

public final class Piece {
    private final PieceType type;
    private final Color color;
    private boolean hasMoved;

    public Piece(PieceType type, Color color) {
        this.type = type;
        this.color = color;
        this.hasMoved = false;
    }

    public PieceType getType() { return type; }
    public Color getColor() { return color; }
    public boolean hasMoved() { return hasMoved; }
    public void markMoved() { this.hasMoved = true; }

    public Piece copy() {
        Piece p = new Piece(type, color);
        p.hasMoved = this.hasMoved;
        return p;
    }
}
