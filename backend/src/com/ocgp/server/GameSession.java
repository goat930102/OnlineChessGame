package com.ocgp.server;

import java.util.List;
import java.util.Map;

public interface GameSession {
    GameType getGameType();

    void start(List<String> playerIds);

    void makeMove(String playerId, Map<String, Object> payload);

    Map<String, Object> toDto();

    String getStatus();

    String getCurrentPlayerId();
}
