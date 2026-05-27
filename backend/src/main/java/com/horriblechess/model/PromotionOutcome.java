package com.horriblechess.model;

public enum PromotionOutcome {
    KNIGHT(PieceType.KNIGHT, "Knight"),
    BISHOP(PieceType.BISHOP, "Bishop"),
    ROOK(PieceType.ROOK, "Rook"),
    QUEEN(PieceType.QUEEN, "Queen"),
    KING(PieceType.KING, "King"),
    FAILED(null, "Failed");

    private final PieceType pieceType;
    private final String label;

    PromotionOutcome(PieceType pieceType, String label) {
        this.pieceType = pieceType;
        this.label = label;
    }

    public PieceType pieceType() { return pieceType; }
    public String label() { return label; }
    public boolean failed() { return pieceType == null; }
}
