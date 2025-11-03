package com.ocgp.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Room {
    private final String id;
    private final String name;
    private final GameType gameType;
    private final String hostUserId;
    private final boolean privateRoom;
    private final Instant createdAt;
    private final LinkedHashSet<String> playerIds = new LinkedHashSet<>();
    private boolean started;
    private GameSession gameSession;

    public Room(String name, GameType gameType, String hostUserId, boolean privateRoom, Instant createdAt) {
        this(UUID.randomUUID().toString(), name, gameType, hostUserId, privateRoom, createdAt);
    }

    public Room(String id, String name, GameType gameType, String hostUserId, boolean privateRoom, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.gameType = gameType;
        this.hostUserId = hostUserId;
        this.privateRoom = privateRoom;
        this.createdAt = createdAt;
    }

    public synchronized String getId() {
        return id;
    }

    public synchronized String getName() {
        return name;
    }

    public synchronized GameType getGameType() {
        return gameType;
    }

    public synchronized Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized void addPlayer(String userId) {
        if (started) {
            throw new HttpStatusException(409, "Game already started");
        }
        if (playerIds.contains(userId)) {
            return;
        }
        if (playerIds.size() >= 2) {
            throw new HttpStatusException(409, "Room is full");
        }
        playerIds.add(userId);
    }

    public synchronized void ensurePlayer(String userId) {
        if (!playerIds.contains(userId)) {
            throw new HttpStatusException(403, "You are not part of this room");
        }
    }

    public synchronized void ensureHost(String userId) {
        if (!hostUserId.equals(userId)) {
            throw new HttpStatusException(403, "Only the host can perform this action");
        }
    }

    public synchronized void startGame() {
        if (started) {
            throw new HttpStatusException(409, "Game already started");
        }
        if (playerIds.size() < 2) {
            throw new HttpStatusException(409, "Need two players to start");
        }
        this.gameSession = switch (gameType) {
            case GOBANG -> new GobangGameSession();
            case CHINESE_CHESS -> new ChineseChessGameSession();
        };
        this.gameSession.start(new ArrayList<>(playerIds));
        this.started = true;
    }

    public synchronized void submitMove(String userId, Map<String, Object> payload) {
        if (!started || gameSession == null) {
            throw new HttpStatusException(409, "Game not started");
        }
        ensurePlayer(userId);
        gameSession.makeMove(userId, payload);
    }

    public synchronized Map<String, Object> toDto(DataStore store) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", id);
        dto.put("name", name);
        dto.put("gameType", gameType.name());
        dto.put("gameTypeName", gameType.getDisplayName());
        dto.put("hostUserId", hostUserId);
        dto.put("private", privateRoom);
        dto.put("createdAt", createdAt.toString());
        List<Map<String, Object>> players = new ArrayList<>();
        for (String playerId : playerIds) {
            User user = store.getUserById(playerId);
            players.add(user.toPublicDto());
        }
        dto.put("players", players);
        dto.put("playerIds", new ArrayList<>(playerIds));
        dto.put("started", started);
        if (started && gameSession != null) {
            dto.put("status", gameSession.getStatus());
            dto.put("currentPlayerId", gameSession.getCurrentPlayerId());
            dto.put("gameState", gameSession.toDto());
        } else {
            dto.put("status", "WAITING");
            dto.put("currentPlayerId", null);
            dto.put("gameState", null);
        }
        return dto;
    }
}
