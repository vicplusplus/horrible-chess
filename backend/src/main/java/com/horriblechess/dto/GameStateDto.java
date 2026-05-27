package com.horriblechess.dto;

import com.horriblechess.model.Board;
import com.horriblechess.model.Color;
import com.horriblechess.model.Game;
import com.horriblechess.model.GameStatus;
import com.horriblechess.model.Piece;
import com.horriblechess.model.Position;

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
        List<MoveDto> history
) {
    public record PieceDto(String type, String color, boolean hasMoved) {}
    public record MoveDto(int fromFile, int fromRank, int toFile, int toRank,
                          String piece, String mover, String captured, String promotion) {}

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
                    rec.move().promotion() == null ? null : rec.move().promotion().name()));
        }
        return new GameStateDto(
                game.getId(),
                game.getStatus(),
                game.getTurn(),
                game.getEnPassantTarget(),
                squares,
                game.getWhitePlayerId() != null,
                game.getBlackPlayerId() != null,
                history
        );
    }
}
