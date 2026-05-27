package com.horriblechess.dto;

public record MoveRequest(
        String playerId,
        int fromFile,
        int fromRank,
        int toFile,
        int toRank,
        String promotion
) {}
