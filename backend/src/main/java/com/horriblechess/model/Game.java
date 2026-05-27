package com.horriblechess.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Game {
    private final String id;
    private final Board board;
    private Color turn;
    private GameStatus status;
    private Position enPassantTarget;
    private String whitePlayerId;
    private String blackPlayerId;
    private final List<MoveRecord> history = new ArrayList<>();
    private RandomEvent lastEvent;
    private long eventSeq;

    public Game(String id) {
        this(id, Board.startingPosition());
    }

    public Game(String id, Board board) {
        this.id = id;
        this.board = board;
        this.turn = Color.WHITE;
        this.status = GameStatus.WAITING_FOR_OPPONENT;
        this.enPassantTarget = null;
    }

    public String getId() { return id; }
    public Board getBoard() { return board; }
    public Color getTurn() { return turn; }
    public GameStatus getStatus() { return status; }
    public Position getEnPassantTarget() { return enPassantTarget; }
    public String getWhitePlayerId() { return whitePlayerId; }
    public String getBlackPlayerId() { return blackPlayerId; }
    public List<MoveRecord> getHistory() { return history; }

    public String addPlayer() {
        String token = UUID.randomUUID().toString();
        if (whitePlayerId == null) {
            whitePlayerId = token;
        } else if (blackPlayerId == null) {
            blackPlayerId = token;
            status = GameStatus.IN_PROGRESS;
        } else {
            return null;
        }
        return token;
    }

    public Color colorOf(String playerId) {
        if (playerId == null) return null;
        if (playerId.equals(whitePlayerId)) return Color.WHITE;
        if (playerId.equals(blackPlayerId)) return Color.BLACK;
        return null;
    }

    public void setEnPassantTarget(Position p) { this.enPassantTarget = p; }
    public void setTurn(Color c) { this.turn = c; }
    public void setStatus(GameStatus s) { this.status = s; }

    public RandomEvent getLastEvent() { return lastEvent; }
    public long getEventSeq() { return eventSeq; }
    public void recordEvent(RandomEvent e) {
        this.lastEvent = e;
        this.eventSeq++;
    }

    public record MoveRecord(Move move, PieceType pieceType, Color mover,
                             PieceType captured, PromotionOutcome promotion) {}
}
