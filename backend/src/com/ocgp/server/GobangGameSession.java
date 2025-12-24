package com.ocgp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

public class GobangGameSession implements GameSession {
    private static final int BOARD_SIZE = 15;

    private final int[][] board = new int[BOARD_SIZE][BOARD_SIZE];
    private final List<Map<String, Object>> moves = new ArrayList<>();
    private List<String> players = List.of();
    private int currentPlayerIndex = 0;
    private String status = "READY";
    private String winnerId;
    private boolean draw;
    private Instant startedAt;

    @Override
    public GameType getGameType() {
        return GameType.GOBANG;
    }

    @Override
    public synchronized void start(List<String> playerIds) {
        if (playerIds == null || playerIds.size() < 2) {
            throw new HttpStatusException(409, "Gobang requires exactly two players");
        }
        this.players = List.copyOf(playerIds);
        for (int x = 0; x < BOARD_SIZE; x++) {
            for (int y = 0; y < BOARD_SIZE; y++) {
                board[x][y] = 0;
            }
        }
        moves.clear();
        currentPlayerIndex = 0;
        status = "IN_PROGRESS";
        winnerId = null;
        draw = false;
        startedAt = Instant.now();
    }

    @Override
    public synchronized void makeMove(String playerId, Map<String, Object> payload) {
        ensureInProgress();
        if (!playerId.equals(players.get(currentPlayerIndex))) {
            throw new HttpStatusException(409, "Not your turn");
        }
        int x = extractInt(payload.get("x"), "x");
        int y = extractInt(payload.get("y"), "y");
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
            throw new HttpStatusException(400, "Move out of bounds");
        }
        if (board[x][y] != 0) {
            throw new HttpStatusException(409, "Cell already occupied");
        }
        int stone = currentPlayerIndex == 0 ? 1 : -1;
        board[x][y] = stone;
        Map<String, Object> move = new HashMap<>();
        move.put("playerId", playerId);
        move.put("x", x);
        move.put("y", y);
        move.put("stone", stone);
        move.put("moveNumber", moves.size() + 1);
        moves.add(move);
        if (hasFiveInRow(x, y, stone)) {
            winnerId = playerId;
            status = "FINISHED";
            return;
        }
        if (moves.size() >= BOARD_SIZE * BOARD_SIZE) {
            status = "FINISHED";
            draw = true;
            return;
        }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
    }

    @Override
    public synchronized Map<String, Object> toDto() {
        Map<String, Object> dto = new HashMap<>();
        List<List<Integer>> grid = new ArrayList<>();
        for (int x = 0; x < BOARD_SIZE; x++) {
            List<Integer> row = new ArrayList<>(BOARD_SIZE);
            for (int y = 0; y < BOARD_SIZE; y++) {
                row.add(board[x][y]);
            }
            grid.add(row);
        }
        dto.put("type", getGameType().name());
        dto.put("boardSize", BOARD_SIZE);
        dto.put("board", grid);
        dto.put("moves", List.copyOf(moves));
        dto.put("status", status);
        dto.put("winnerId", winnerId);
        dto.put("draw", draw);
        dto.put("playerOrder", players);
        dto.put("startedAt", startedAt != null ? startedAt.toString() : null);
        return dto;
    }

    @Override
    public synchronized String getStatus() {
        return status;
    }

    @Override
    public synchronized String getCurrentPlayerId() {
        if (!"IN_PROGRESS".equals(status) || players.isEmpty()) {
            return null;
        }
        return players.get(currentPlayerIndex);
    }

    @Override
    public synchronized void forceWin(String winner) {
        if (!"IN_PROGRESS".equals(status)) {
            return;
        }
        status = "FINISHED";
        winnerId = winner;
    }

    @Override
    public List<String> getPlayerOrder() {
        return players;
    }

    @Override
    public Instant getStartedAt() {
        return startedAt;
    }

    private void ensureInProgress() {
        if (!"IN_PROGRESS".equals(status)) {
            throw new HttpStatusException(409, "Game already finished");
        }
    }

    private boolean hasFiveInRow(int x, int y, int stone) {
        int[][] directions = {
                {1, 0}, {0, 1}, {1, 1}, {1, -1}
        };
        for (int[] dir : directions) {
            int count = 1;
            count += countDirection(x, y, dir[0], dir[1], stone);
            count += countDirection(x, y, -dir[0], -dir[1], stone);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private int countDirection(int x, int y, int dx, int dy, int stone) {
        int count = 0;
        int cx = x + dx;
        int cy = y + dy;
        while (cx >= 0 && cx < BOARD_SIZE && cy >= 0 && cy < BOARD_SIZE && board[cx][cy] == stone) {
            count++;
            cx += dx;
            cy += dy;
        }
        return count;
    }

    private int extractInt(Object raw, String field) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new HttpStatusException(400, "Invalid integer for " + field);
    }
}
