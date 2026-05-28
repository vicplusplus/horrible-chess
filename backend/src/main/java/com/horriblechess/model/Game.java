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
    private final List<JournalEntry> journal = new ArrayList<>();
    private RandomEvent lastEvent;
    private long eventSeq;
    private TurnAction currentTurnAction;
    private int movesRemaining;
    // Pieces the player is forced to choose among this turn (FORCED action).
    // Empty when not forced; the player may move any one of them.
    private final List<Position> forcedPiecePositions = new ArrayList<>();
    private final List<Position> eventSquares = new ArrayList<>();
    private final List<Duck> ducks = new ArrayList<>();
    private Color pendingSkip;
    // Epoch millis of the last meaningful activity; used to evict abandoned and
    // finished games so the in-memory map doesn't grow without bound.
    private volatile long lastTouched = System.currentTimeMillis();

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
    public long getLastTouched() { return lastTouched; }
    public void touch(long epochMillis) { this.lastTouched = epochMillis; }
    public Board getBoard() { return board; }
    public Color getTurn() { return turn; }
    public GameStatus getStatus() { return status; }
    public Position getEnPassantTarget() { return enPassantTarget; }
    public String getWhitePlayerId() { return whitePlayerId; }
    public String getBlackPlayerId() { return blackPlayerId; }
    public List<MoveRecord> getHistory() { return history; }
    public List<JournalEntry> getJournal() { return journal; }

    public void log(JournalEntry.JournalKind kind, Color color, String text) {
        journal.add(new JournalEntry(kind, color, text));
    }

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

    public TurnAction getCurrentTurnAction() { return currentTurnAction; }
    public void setCurrentTurnAction(TurnAction a) { this.currentTurnAction = a; }
    public int getMovesRemaining() { return movesRemaining; }
    public void setMovesRemaining(int n) { this.movesRemaining = n; }
    public List<Position> getForcedPiecePositions() { return forcedPiecePositions; }
    public void setForcedPiecePositions(List<Position> ps) {
        forcedPiecePositions.clear();
        forcedPiecePositions.addAll(ps);
    }
    public void clearForcedPieces() { forcedPiecePositions.clear(); }

    public List<Position> getEventSquares() { return eventSquares; }
    public List<Duck> getDucks() { return ducks; }
    public Color getPendingSkip() { return pendingSkip; }
    public void setPendingSkip(Color c) { this.pendingSkip = c; }

    public record MoveRecord(Move move, PieceType pieceType, Color mover,
                             PieceType captured, PromotionOutcome promotion) {}
}
