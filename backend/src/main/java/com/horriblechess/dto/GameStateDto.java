package com.horriblechess.dto;

import com.horriblechess.model.Board;
import com.horriblechess.model.Color;
import com.horriblechess.model.Duck;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.JournalEntry;
import com.horriblechess.model.Piece;
import com.horriblechess.model.Position;
import com.horriblechess.model.RandomEvent;
import com.horriblechess.model.TurnAction;

import java.util.ArrayList;
import java.util.List;

public record GameStateDto(
        String id,
        GameStatus status,
        Color turn,
        Position enPassantTarget,
        List<List<PieceDto>> squares,
        boolean whiteJoined,
        boolean blackJoined,
        List<MoveDto> history,
        List<JournalEntryDto> journal,
        RandomEvent lastEvent,
        long eventSeq,
        TurnAction currentTurnAction,
        int movesRemaining,
        Position forcedPiecePosition,
        List<Position> eventSquares,
        List<Duck> ducks,
        Color pendingSkip
) {
    public record PieceDto(String type, String color, boolean hasMoved) {}
    public record MoveDto(int fromFile, int fromRank, int toFile, int toRank,
                          String piece, String mover, String captured, String promotion) {}
    public record JournalEntryDto(String kind, String color, String text) {}

    public static GameStateDto from(Game game) {
        Board board = game.getBoard();
        List<List<PieceDto>> squares = new ArrayList<>(8);
        for (int f = 0; f < 8; f++) {
            List<PieceDto> col = new ArrayList<>(8);
            for (int r = 0; r < 8; r++) {
                Piece p = board.get(f, r);
                col.add(p == null ? null : new PieceDto(p.getType().name(), p.getColor().name(), p.hasMoved()));
            }
            squares.add(col);
        }
        List<MoveDto> history = new ArrayList<>();
        for (Game.MoveRecord rec : game.getHistory()) {
            history.add(new MoveDto(
                    rec.move().from().file(), rec.move().from().rank(),
                    rec.move().to().file(), rec.move().to().rank(),
                    rec.pieceType().name(),
                    rec.mover().name(),
                    rec.captured() == null ? null : rec.captured().name(),
                    rec.promotion() == null ? null : rec.promotion().label()));
        }
        List<JournalEntryDto> journal = new ArrayList<>();
        for (JournalEntry e : game.getJournal()) {
            journal.add(new JournalEntryDto(
                    e.kind().name(),
                    e.color() == null ? null : e.color().name(),
                    e.text()));
        }
        return new GameStateDto(
                game.getId(),
                game.getStatus(),
                game.getTurn(),
                game.getEnPassantTarget(),
                squares,
                game.getWhitePlayerId() != null,
                game.getBlackPlayerId() != null,
                history,
                journal,
                game.getLastEvent(),
                game.getEventSeq(),
                game.getCurrentTurnAction(),
                game.getMovesRemaining(),
                game.getForcedPiecePosition(),
                new ArrayList<>(game.getEventSquares()),
                new ArrayList<>(game.getDucks()),
                game.getPendingSkip()
        );
    }
}
