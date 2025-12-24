package com.ocgp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

public class ChineseChessGameSession implements GameSession {
    private static final int ROWS = 10;
    private static final int COLS = 9;

    private final Piece[][] board = new Piece[ROWS][COLS];
    private final List<Map<String, Object>> moves = new ArrayList<>();
    private List<String> players = List.of();
    private int currentPlayerIndex = 0;
    private String status = "READY";
    private String winnerId;
    private Instant startedAt;

    @Override
    public GameType getGameType() {
        return GameType.CHINESE_CHESS;
    }

    @Override
    public synchronized void start(List<String> playerIds) {
        if (playerIds == null || playerIds.size() < 2) {
            throw new HttpStatusException(409, "Chinese chess requires two players");
        }
        this.players = List.copyOf(playerIds);
        resetBoard();
        moves.clear();
        currentPlayerIndex = 0; // Red starts
        status = "IN_PROGRESS";
        winnerId = null;
        startedAt = Instant.now();
    }

    @Override
    public synchronized void makeMove(String playerId, Map<String, Object> payload) {
        ensureInProgress();
        if (!playerId.equals(players.get(currentPlayerIndex))) {
            throw new HttpStatusException(409, "Not your turn");
        }

        int fromRow = extractInt(payload.get("fromRow"), "fromRow");
        int fromCol = extractInt(payload.get("fromCol"), "fromCol");
        int toRow = extractInt(payload.get("toRow"), "toRow");
        int toCol = extractInt(payload.get("toCol"), "toCol");

        validateBounds(fromRow, fromCol);
        validateBounds(toRow, toCol);

        Piece piece = board[fromRow][fromCol];
        if (piece == null) {
            throw new HttpStatusException(400, "No piece at source tile");
        }
        PieceColor expectedColor = currentPlayerIndex == 0 ? PieceColor.RED : PieceColor.BLACK;
        if (piece.color != expectedColor) {
            throw new HttpStatusException(403, "Cannot move opponent piece");
        }
        Piece target = board[toRow][toCol];
        if (target != null && target.color == piece.color) {
            throw new HttpStatusException(403, "Cannot capture own piece");
        }

        if (!isLegalMove(piece, fromRow, fromCol, toRow, toCol, target != null)) {
            throw new HttpStatusException(400, "Illegal move for " + piece.type);
        }

        Piece captured = target;
        
        performMove(piece, fromRow, fromCol, toRow, toCol);

        if (generalsFacing()) {
            performMove(piece, toRow, toCol, fromRow, fromCol);
            if (captured != null) {
                placePiece(captured, toRow, toCol);
            }
            throw new HttpStatusException(400, "Generals cannot face each other");
        }

        if (isPlayerInCheck(piece.color)) {
            performMove(piece, toRow, toCol, fromRow, fromCol);
            if (captured != null) {
                placePiece(captured, toRow, toCol);
            }
            throw new HttpStatusException(400, "Cannot make a move that leaves king in check");
        }


        Map<String, Object> move = new HashMap<>();
        move.put("playerId", playerId);
        move.put("fromRow", fromRow);
        move.put("fromCol", fromCol);
        move.put("toRow", toRow);
        move.put("toCol", toCol);
        move.put("piece", piece.type.name());
        move.put("color", piece.color.name());
        move.put("moveNumber", moves.size() + 1);

        PieceColor opponentColor = (piece.color == PieceColor.RED) ? PieceColor.BLACK : PieceColor.RED;
        boolean isCheck = isPlayerInCheck(opponentColor);
        move.put("isCheck", isCheck);

        if (captured != null) {
            move.put("captured", captured.type.name());
            move.put("capturedColor", captured.color.name());
        }
        moves.add(move);

        if (captured != null && captured.type == PieceType.GENERAL) {
            winnerId = playerId;
            status = "FINISHED";
            return;
        }

        // 若輪到對手但其無任何合法行棋，對局結束（當前行棋者勝）
        if (!hasAnyLegalMove(opponentColor)) {
            winnerId = playerId;
            status = "FINISHED";
            return;
        }

        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();

    }

    @Override
    public synchronized Map<String, Object> toDto() {
        Map<String, Object> dto = new HashMap<>();
        List<List<Map<String, Object>>> state = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            List<Map<String, Object>> row = new ArrayList<>();
            for (int c = 0; c < COLS; c++) {
                Piece piece = board[r][c];
                if (piece == null) {
                    row.add(null);
                } else {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("type", piece.type.name());
                    entry.put("color", piece.color.name());
                    entry.put("symbol", piece.color.symbolPrefix + piece.type.symbolSuffix);
                    row.add(entry);
                }
            }
            state.add(row);
        }
        dto.put("type", getGameType().name());
        dto.put("board", state);
        dto.put("status", status);
        dto.put("winnerId", winnerId);
        dto.put("moves", List.copyOf(moves));
        dto.put("playerOrder", players);
        dto.put("currentPlayerColor", currentPlayerIndex == 0 ? "RED" : "BLACK");
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

    private void resetBoard() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                board[r][c] = null;
            }
        }
        // Black side (top)
        placePiece(new Piece(PieceType.CHARIOT, PieceColor.BLACK), 0, 0);
        placePiece(new Piece(PieceType.HORSE, PieceColor.BLACK), 0, 1);
        placePiece(new Piece(PieceType.ELEPHANT, PieceColor.BLACK), 0, 2);
        placePiece(new Piece(PieceType.ADVISOR, PieceColor.BLACK), 0, 3);
        placePiece(new Piece(PieceType.GENERAL, PieceColor.BLACK), 0, 4);
        placePiece(new Piece(PieceType.ADVISOR, PieceColor.BLACK), 0, 5);
        placePiece(new Piece(PieceType.ELEPHANT, PieceColor.BLACK), 0, 6);
        placePiece(new Piece(PieceType.HORSE, PieceColor.BLACK), 0, 7);
        placePiece(new Piece(PieceType.CHARIOT, PieceColor.BLACK), 0, 8);

        placePiece(new Piece(PieceType.CANNON, PieceColor.BLACK), 2, 1);
        placePiece(new Piece(PieceType.CANNON, PieceColor.BLACK), 2, 7);

        placePiece(new Piece(PieceType.SOLDIER, PieceColor.BLACK), 3, 0);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.BLACK), 3, 2);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.BLACK), 3, 4);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.BLACK), 3, 6);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.BLACK), 3, 8);

        // Red side (bottom)
        placePiece(new Piece(PieceType.CHARIOT, PieceColor.RED), 9, 0);
        placePiece(new Piece(PieceType.HORSE, PieceColor.RED), 9, 1);
        placePiece(new Piece(PieceType.ELEPHANT, PieceColor.RED), 9, 2);
        placePiece(new Piece(PieceType.ADVISOR, PieceColor.RED), 9, 3);
        placePiece(new Piece(PieceType.GENERAL, PieceColor.RED), 9, 4);
        placePiece(new Piece(PieceType.ADVISOR, PieceColor.RED), 9, 5);
        placePiece(new Piece(PieceType.ELEPHANT, PieceColor.RED), 9, 6);
        placePiece(new Piece(PieceType.HORSE, PieceColor.RED), 9, 7);
        placePiece(new Piece(PieceType.CHARIOT, PieceColor.RED), 9, 8);

        placePiece(new Piece(PieceType.CANNON, PieceColor.RED), 7, 1);
        placePiece(new Piece(PieceType.CANNON, PieceColor.RED), 7, 7);

        placePiece(new Piece(PieceType.SOLDIER, PieceColor.RED), 6, 0);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.RED), 6, 2);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.RED), 6, 4);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.RED), 6, 6);
        placePiece(new Piece(PieceType.SOLDIER, PieceColor.RED), 6, 8);
    }

    private void placePiece(Piece piece, int row, int col) {
        piece.row = row;
        piece.col = col;
        piece.captured = false;
        board[row][col] = piece;
    }

    private void performMove(Piece piece, int fromRow, int fromCol, int toRow, int toCol) {
        board[fromRow][fromCol] = null;
        Piece target = board[toRow][toCol];
        if (target != null) {
            target.captured = true;
        }
        board[toRow][toCol] = piece;
        piece.row = toRow;
        piece.col = toCol;
    }

    private int[] findGeneral(PieceColor color) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Piece p = board[r][c];
                if (p != null && p.type == PieceType.GENERAL && p.color == color) {
                    return new int[]{r, c};
                }
            }
        }
        throw new IllegalStateException("General not found for color " + color);
    }

    private boolean isPlayerInCheck(PieceColor color) {
        int[] generalPos;
        try {
            generalPos = findGeneral(color);
        } catch (IllegalStateException e) {
            return false;
        }
        int genRow = generalPos[0];
        int genCol = generalPos[1];

        PieceColor opponentColor = (color == PieceColor.RED) ? PieceColor.BLACK : PieceColor.RED;
        
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Piece p = board[r][c];
                if (p != null && p.color == opponentColor) {
                    if (isLegalMove(p, r, c, genRow, genCol, true)) {
                        return true; 
                    }
                }
            }
        }
        return false;
    }

    private boolean hasAnyLegalMove(PieceColor color) {
        for (int fromRow = 0; fromRow < ROWS; fromRow++) {
            for (int fromCol = 0; fromCol < COLS; fromCol++) {
                Piece piece = board[fromRow][fromCol];
                if (piece == null || piece.color != color) {
                    continue;
                }

                for (int toRow = 0; toRow < ROWS; toRow++) {
                    for (int toCol = 0; toCol < COLS; toCol++) {
                        if (fromRow == toRow && fromCol == toCol) {
                            continue;
                        }

                        Piece target = board[toRow][toCol];
                        if (target != null && target.color == color) {
                            continue; // 不能吃己方
                        }

                        boolean capturing = (target != null);
                        if (!isLegalMove(piece, fromRow, fromCol, toRow, toCol, capturing)) {
                            continue;
                        }

                        if (isMoveSafeForOwnGeneral(piece, fromRow, fromCol, toRow, toCol)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isMoveSafeForOwnGeneral(Piece piece, int fromRow, int fromCol, int toRow, int toCol) {
        Piece captured = board[toRow][toCol];

        // 模擬走子
        performMove(piece, fromRow, fromCol, toRow, toCol);

        boolean illegal = false;
        // 將帥不可見面
        if (generalsFacing()) {
            illegal = true;
        }
        // 不可走到讓己方仍被將軍
        if (!illegal && isPlayerInCheck(piece.color)) {
            illegal = true;
        }

        // 回復棋盤
        performMove(piece, toRow, toCol, fromRow, fromCol);
        if (captured != null) {
            // 還原被吃子
            placePiece(captured, toRow, toCol);
        }

        return !illegal;
    }

    private boolean generalsFacing() {
        int redCol = -1;
        int redRow = -1;
        int blackCol = -1;
        int blackRow = -1;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Piece piece = board[r][c];
                if (piece != null && piece.type == PieceType.GENERAL) {
                    if (piece.color == PieceColor.RED) {
                        redRow = r;
                        redCol = c;
                    } else {
                        blackRow = r;
                        blackCol = c;
                    }
                }
            }
        }
        if (redCol == -1 || blackCol == -1 || redCol != blackCol) {
            return false;
        }
        int start = Math.min(redRow, blackRow) + 1;
        int end = Math.max(redRow, blackRow);
        for (int r = start; r < end; r++) {
            if (board[r][redCol] != null) {
                return false;
            }
        }
        return true;
    }

    private boolean isLegalMove(Piece piece, int fromRow, int fromCol, int toRow, int toCol, boolean capturing) {
        int dRow = toRow - fromRow;
        int dCol = toCol - fromCol;
        int absRow = Math.abs(dRow);
        int absCol = Math.abs(dCol);
        switch (piece.type) {
            case GENERAL -> {
                if (absRow + absCol != 1) {
                    return false;
                }
                return inPalace(piece.color, toRow, toCol);
            }
            case ADVISOR -> {
                if (absRow != 1 || absCol != 1) {
                    return false;
                }
                return inPalace(piece.color, toRow, toCol);
            }
            case ELEPHANT -> {
                if (absRow != 2 || absCol != 2) {
                    return false;
                }
                if (crossesRiver(piece.color, toRow)) {
                    return false;
                }
                int midRow = fromRow + dRow / 2;
                int midCol = fromCol + dCol / 2;
                return board[midRow][midCol] == null;
            }
            case HORSE -> {
                if (!((absRow == 2 && absCol == 1) || (absRow == 1 && absCol == 2))) {
                    return false;
                }
                if (absRow == 2) {
                    int blockRow = fromRow + (dRow / 2);
                    return board[blockRow][fromCol] == null;
                } else {
                    int blockCol = fromCol + (dCol / 2);
                    return board[fromRow][blockCol] == null;
                }
            }
            case CHARIOT -> {
                if (absRow != 0 && absCol != 0) {
                    return false;
                }
                return clearPath(fromRow, fromCol, toRow, toCol);
            }
            case CANNON -> {
                if (absRow != 0 && absCol != 0) {
                    return false;
                }
                int blockers = countBlockers(fromRow, fromCol, toRow, toCol);
                if (capturing) {
                    return blockers == 1;
                }
                return blockers == 0;
            }
            case SOLDIER -> {
                int forward = piece.color == PieceColor.RED ? -1 : 1;
                if (dRow == forward && dCol == 0) {
                    return true;
                }
                if (hasCrossedRiver(piece.color, fromRow) && dRow == 0 && Math.abs(dCol) == 1) {
                    return true;
                }
                return false;
            }
            default -> throw new IllegalStateException("Unhandled piece type " + piece.type);
        }
    }

    private boolean inPalace(PieceColor color, int row, int col) {
        if (col < 3 || col > 5) {
            return false;
        }
        if (color == PieceColor.RED) {
            return row >= 7 && row <= 9;
        }
        return row >= 0 && row <= 2;
    }

    private boolean crossesRiver(PieceColor color, int toRow) {
        if (color == PieceColor.RED) {
            return toRow < 5;
        }
        return toRow > 4;
    }

    private boolean hasCrossedRiver(PieceColor color, int fromRow) {
        if (color == PieceColor.RED) {
            return fromRow <= 4;
        }
        return fromRow >= 5;
    }

    private boolean clearPath(int fromRow, int fromCol, int toRow, int toCol) {
        int dRow = Integer.compare(toRow, fromRow);
        int dCol = Integer.compare(toCol, fromCol);
        int row = fromRow + dRow;
        int col = fromCol + dCol;
        while (row != toRow || col != toCol) {
            if (board[row][col] != null) {
                return false;
            }
            row += dRow;
            col += dCol;
        }
        return true;
    }

    private int countBlockers(int fromRow, int fromCol, int toRow, int toCol) {
        int count = 0;
        int dRow = Integer.compare(toRow, fromRow);
        int dCol = Integer.compare(toCol, fromCol);
        int row = fromRow + dRow;
        int col = fromCol + dCol;
        while (row != toRow || col != toCol) {
            if (board[row][col] != null) {
                count++;
            }
            row += dRow;
            col += dCol;
        }
        return count;
    }

    private void validateBounds(int row, int col) {
        if (row < 0 || row >= ROWS || col < 0 || col >= COLS) {
            throw new HttpStatusException(400, "Coordinates out of bounds");
        }
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

    private enum PieceColor {
        RED("R-"),
        BLACK("B-");

        final String symbolPrefix;

        PieceColor(String symbolPrefix) {
            this.symbolPrefix = symbolPrefix;
        }
    }

    private enum PieceType {
        GENERAL("GEN"),
        ADVISOR("ADV"),
        ELEPHANT("ELE"),
        HORSE("HOR"),
        CHARIOT("CAR"),
        CANNON("CAN"),
        SOLDIER("SOL");

        final String symbolSuffix;

        PieceType(String symbolSuffix) {
            this.symbolSuffix = symbolSuffix;
        }
    }

    private static final class Piece {
        final PieceType type;
        final PieceColor color;
        int row;
        int col;
        boolean captured;

        Piece(PieceType type, PieceColor color) {
            this.type = type;
            this.color = color;
        }
    }
}
