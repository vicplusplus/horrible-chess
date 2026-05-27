package com.horriblechess.dto;

import com.horriblechess.model.Color;

public record JoinResponse(String gameId, String playerId, Color color) {}
