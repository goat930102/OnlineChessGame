package com.ocgp.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Logger;

public class Room {
    private static final Logger LOGGER = Logger.getLogger(Room.class.getName());

    private final String id;
    private String name;
    private GameType gameType;
    private String hostUserId;
    private boolean privateRoom;
    private String inviteCode;
    private final Instant createdAt;
    private final LinkedHashSet<String> playerIds = new LinkedHashSet<>();
    private boolean started;
    private GameSession gameSession;
    private final Map<String, Instant> disconnectedUntil = new HashMap<>();
    private Instant turnDeadline;
    private Instant gameStartedAt;

    public Room(String name, GameType gameType, String hostUserId, boolean privateRoom, String inviteCode, Instant createdAt) {
        this(UUID.randomUUID().toString(), name, gameType, hostUserId, privateRoom, inviteCode, createdAt);
    }

    public Room(String id, String name, GameType gameType, String hostUserId, boolean privateRoom, String inviteCode, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.gameType = gameType;
        this.hostUserId = hostUserId;
        this.privateRoom = privateRoom;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
        this.turnDeadline = null;
        this.gameStartedAt = null;
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

    public synchronized String getHostUserId() {
        return hostUserId;
    }

    public synchronized Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized boolean isPrivateRoom() {
        return privateRoom;
    }

    public synchronized String getInviteCode() {
        return inviteCode;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized int getPlayerCount() {
        return playerIds.size();
    }

    public synchronized List<String> getPlayerIds() {
        return new ArrayList<>(playerIds);
    }

    public synchronized void addPlayer(String userId) {
        if (started) {
            // 已開局：視為重連，移除斷線標記
            if (!playerIds.contains(userId)) {
                if (playerIds.size() >= 2) {
                    throw new HttpStatusException(409, "Room is full");
                }
                playerIds.add(userId);
            }
            disconnectedUntil.remove(userId);
            LOGGER.info(() -> String.format("User %s rejoined during game in room %s", userId, id));
            return;
        }
        if (playerIds.contains(userId)) {
            return;
        }
        if (playerIds.size() >= 2) {
            throw new HttpStatusException(409, "Room is full");
        }
        playerIds.add(userId);
        LOGGER.info(() -> String.format("User %s joined room %s", userId, id));
    }

    public synchronized Instant removePlayer(String userId) {
        if (!playerIds.contains(userId)) {
            return null;
        }
        LOGGER.info(() -> String.format("User %s left room %s", userId, id));

        // host 離開：若房間尚有人，轉移 host 給第一位剩餘玩家（維持可用性）
        if (hostUserId != null && hostUserId.equals(userId) && playerIds.size() > 1) {
            // 先選擇下一位仍在線的玩家
            for (String pid : playerIds) {
                if (!pid.equals(userId) && !disconnectedUntil.containsKey(pid)) {
                    hostUserId = pid;
                    break;
                }
            }
        }

        if (started) {
            // 開局中：標記離線 30 秒，可重連
            Instant expiry = Instant.now().plusSeconds(30);
            disconnectedUntil.put(userId, expiry);
            return expiry;
        }

        playerIds.remove(userId);

        // 未開局：移除玩家後，若空房則保持原邏輯，由 DataStore 決定是否刪除
        return null;
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
        this.gameStartedAt = Instant.now();
        this.turnDeadline = gameStartedAt.plusSeconds(15);
        LOGGER.info(() -> String.format("Room %s started game (%s)", id, gameType));
    }

    public synchronized void restartGame(String userId) {
        ensureHost(userId);

        if (!started || gameSession == null) {
            throw new HttpStatusException(409, "Game not started");
        }
        if (!"FINISHED".equals(gameSession.getStatus())) {
            throw new HttpStatusException(409, "Game is not finished");
        }

        // 立即重新建立新對局，保留玩家順序
        this.gameSession = switch (gameType) {
            case GOBANG -> new GobangGameSession();
            case CHINESE_CHESS -> new ChineseChessGameSession();
        };
        this.gameSession.start(new ArrayList<>(playerIds));
        this.started = true;
        this.gameStartedAt = Instant.now();
        this.turnDeadline = gameStartedAt.plusSeconds(15);
        LOGGER.info(() -> String.format("Room %s restarted game (%s)", id, gameType));
    }

    public synchronized void submitMove(String userId, Map<String, Object> payload) {
        if (!started || gameSession == null) {
            throw new HttpStatusException(409, "Game not started");
        }
        ensurePlayer(userId);
        gameSession.makeMove(userId, payload);
        refreshTurnDeadline();
    }

    public synchronized Map<String, Object> toDto(DataStore store) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", id);
        dto.put("name", name);
        dto.put("gameType", gameType.name());
        dto.put("gameTypeName", gameType.getDisplayName());
        dto.put("hostUserId", hostUserId);
        dto.put("private", privateRoom);
        dto.put("inviteCode", inviteCode);
        dto.put("createdAt", createdAt.toString());
        dto.put("turnDeadline", turnDeadline != null ? turnDeadline.toString() : null);
        Instant startedAtDto = gameStartedAt;
        if (gameSession != null && gameSession.getStartedAt() != null) {
            startedAtDto = gameSession.getStartedAt();
        }
        dto.put("startedAt", startedAtDto != null ? startedAtDto.toString() : null);
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

    public synchronized String getStatus() {
        if (started && gameSession != null) {
            return gameSession.getStatus();
        }
        return "WAITING";
    }

    public synchronized String getCurrentPlayerId() {
        if (started && gameSession != null) {
            return gameSession.getCurrentPlayerId();
        }
        return null;
    }

    public synchronized void ensureInviteCode(String providedCode) {
        if (!privateRoom) {
            return;
        }
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new HttpStatusException(403, "Invite code not set");
        }
        if (!inviteCode.equals(providedCode)) {
            throw new HttpStatusException(403, "Invalid invite code");
        }
    }

    public synchronized void updateSettings(String userId, String newName, GameType newGameType, boolean newPrivateFlag, String newInviteCode) {
        ensureHost(userId);
        if (newName != null && !newName.isBlank()) {
            this.name = newName.trim();
        }
        if (newGameType != null && !newGameType.equals(this.gameType) && !started) {
            this.gameType = newGameType;
        } else if (newGameType != null && started) {
            throw new HttpStatusException(409, "Cannot change game type after start");
        }
        this.privateRoom = newPrivateFlag;
        this.inviteCode = newInviteCode;
    }

    public synchronized void timeoutDisconnected(String userId) {
        if (!disconnectedUntil.containsKey(userId)) {
            return;
        }
        if (!started || gameSession == null) {
            disconnectedUntil.remove(userId);
            return;
        }
        Instant expiry = disconnectedUntil.get(userId);
        if (expiry != null && Instant.now().isAfter(expiry)) {
            // 超時：結束對局回到等待
            started = false;
            gameSession = null;
            disconnectedUntil.clear();
            LOGGER.info(() -> String.format("Disconnected timeout for room %s, resetting game", id));
        }
    }

    public synchronized boolean checkTurnTimeout() {
        if (!started || gameSession == null || turnDeadline == null) {
            return false;
        }
        Instant now = Instant.now();
        if (now.isBefore(turnDeadline)) {
            return false;
        }
        String current = gameSession.getCurrentPlayerId();
        List<String> order = gameSession.getPlayerOrder();
        if (current == null || order.size() < 2) {
            return false;
        }
        String winner = order.get(0).equals(current) ? order.get(1) : order.get(0);
        gameSession.forceWin(winner);
        turnDeadline = null;
        LOGGER.info(() -> String.format("Room %s timeout, winner %s", id, winner));
        return true;
    }

    public synchronized void refreshTurnDeadline() {
        if (!started) return;
        this.turnDeadline = Instant.now().plusSeconds(15);
    }

    public synchronized Instant getTurnDeadline() {
        return turnDeadline;
    }

    public synchronized Instant getStartedAt() {
        return gameStartedAt;
    }

    public synchronized void setTurnDeadline(Instant deadline) {
        this.turnDeadline = deadline;
    }

    public synchronized void setStartedAt(Instant startedAt) {
        this.gameStartedAt = startedAt;
    }

    public synchronized void setStarted(boolean started) {
        this.started = started;
    }
}
