package com.ocgp.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ApiHandler implements HttpHandler {
    private static final String API_ROOT = "/api";
    private static final int EMPTY_ROOM_TTL_SECONDS = 30;

    private final DataStore dataStore;

    public ApiHandler(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            addCorsHeaders(exchange.getResponseHeaders());
            String method = exchange.getRequestMethod().toUpperCase();
            if ("OPTIONS".equals(method)) {
                HttpUtils.sendNoContent(exchange);
                return;
            }
            String path = normalizePath(exchange.getRequestURI().getPath());
            if ("POST".equals(method) && "/register".equals(path)) {
                handleRegister(exchange);
            } else if ("POST".equals(method) && "/login".equals(path)) {
                handleLogin(exchange);
            } else if ("GET".equals(method) && "/me".equals(path)) {
                handleMe(exchange);
            } else if ("GET".equals(method) && "/games".equals(path)) {
                handleGames(exchange);
            } else if ("/rooms".equals(path)) {
                handleRoomsRoot(exchange);
            } else if (path.startsWith("/rooms/")) {
                handleRoomsSubresource(exchange, path.substring("/rooms/".length()));
            } else {
                throw new HttpStatusException(404, "Unknown API endpoint");
            }
        } catch (HttpStatusException ex) {
            Map<String, Object> error = Map.of("error", ex.getMessage());
            HttpUtils.sendJson(exchange, ex.getStatus(), error);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> error = Map.of("error", ex.getMessage());
            HttpUtils.sendJson(exchange, 400, error);
        } catch (Exception ex) {
            Map<String, Object> error = Map.of("error", "Internal server error", "detail", ex.getMessage());
            HttpUtils.sendJson(exchange, 500, error);
        }
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        Map<String, Object> payload = readJsonObject(exchange);
        String username = asString(payload.get("username"), "username");
        String password = asString(payload.get("password"), "password");
        User user = dataStore.register(username, password);
        Session session = dataStore.createSession(user.getId());
        Map<String, Object> response = new HashMap<>();
        response.put("user", user.toPublicDto());
        response.put("token", session.getToken());
        HttpUtils.sendJson(exchange, 201, response);
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        Map<String, Object> payload = readJsonObject(exchange);
        String username = asString(payload.get("username"), "username");
        String password = asString(payload.get("password"), "password");
        User user = dataStore.authenticate(username, password);
        Session session = dataStore.createSession(user.getId());
        Map<String, Object> response = new HashMap<>();
        response.put("user", user.toPublicDto());
        response.put("token", session.getToken());
        HttpUtils.sendJson(exchange, 200, response);
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        User user = requireUser(exchange);
        HttpUtils.sendJson(exchange, 200, Map.of("user", user.toPublicDto()));
    }

    private void handleGames(HttpExchange exchange) throws IOException {
        HttpUtils.sendJson(exchange, 200, Map.of("games", dataStore.getGameCatalog()));
    }

    private void handleRoomsRoot(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        if ("GET".equals(method)) {
            Map<String, String> query = parseQuery(exchange);
            Optional<GameType> filter = Optional.empty();
            if (query.containsKey("gameType")) {
                filter = Optional.of(GameType.fromString(query.get("gameType")));
            }
            List<Map<String, Object>> rooms = new ArrayList<>();
            for (Room room : dataStore.listRooms(filter)) {
                rooms.add(room.toDto(dataStore));
            }
            HttpUtils.sendJson(exchange, 200, Map.of("rooms", rooms));
        } else if ("POST".equals(method)) {
            User user = requireUser(exchange);
            Map<String, Object> payload = readJsonObject(exchange);
            String name = asString(payload.get("name"), "name");
            String gameTypeRaw = asString(payload.get("gameType"), "gameType");
            GameType gameType = GameType.fromString(gameTypeRaw);
            boolean privateRoom = asBoolean(payload.getOrDefault("private", Boolean.FALSE));
            Room room = dataStore.createRoom(user, name, gameType, privateRoom);
            HttpUtils.sendJson(exchange, 201, Map.of("room", room.toDto(dataStore)));
        } else {
            throw new HttpStatusException(405, "Method not allowed");
        }
    }

    private void handleRoomsSubresource(HttpExchange exchange, String remainder) throws IOException {
        String[] parts = remainder.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            throw new HttpStatusException(404, "Room not specified");
        }
        String roomId = parts[0];
        Room room = dataStore.findRoom(roomId);
        String method = exchange.getRequestMethod().toUpperCase();

        if (parts.length == 1) {
            if ("GET".equals(method)) {
                // 查看房間詳情需驗證
                requireUser(exchange);
                HttpUtils.sendJson(exchange, 200, Map.of("room", room.toDto(dataStore)));
            } else {
                throw new HttpStatusException(405, "Unsupported method for room");
            }
            return;
        }

        String action = parts[1];
        switch (action) {
            case "join" -> handleJoinRoom(exchange, room);
            case "leave" -> handleLeaveRoom(exchange, room);
            case "start" -> handleStartRoom(exchange, room);
            case "move" -> handleRoomMove(exchange, room);
            case "restart" -> handleRestartRoom(exchange, room); // ✅ 新增
            default -> throw new HttpStatusException(404, "Unknown room action: " + action);
        }
    }

    private void handleRestartRoom(HttpExchange exchange, Room room) throws IOException {
        ensurePost(exchange);
        User user = requireUser(exchange);

        // 只允許房主，且必須是 FINISHED 才能 restart
        room.restartGame(user.getId());

        HttpUtils.sendJson(exchange, 200, Map.of("room", room.toDto(dataStore)));
    }

    private void handleJoinRoom(HttpExchange exchange, Room room) throws IOException {
        ensurePost(exchange);
        User user = requireUser(exchange);

        // 重新加入：先取消「空房延遲刪除」排程
        dataStore.cancelScheduledRoomDeletion(room.getId());

        room.addPlayer(user.getId());
        HttpUtils.sendJson(exchange, 200, Map.of("room", room.toDto(dataStore)));
    }

    private void handleLeaveRoom(HttpExchange exchange, Room room) throws IOException {
        ensurePost(exchange);
        User user = requireUser(exchange);

        room.removePlayer(user.getId());

        // 房間無人：不立刻刪除，改為 30 秒後刪除（若期間有人加入會取消）
        if (room.getPlayerCount() == 0) {
            dataStore.scheduleRoomDeletionIfEmpty(room.getId());
            HttpUtils.sendJson(exchange, 200, Map.of(
                    "scheduledDeletion", true,
                    "ttlSeconds", EMPTY_ROOM_TTL_SECONDS
            ));
            return;
        }

        HttpUtils.sendJson(exchange, 200, Map.of("room", room.toDto(dataStore)));
    }

    private void handleStartRoom(HttpExchange exchange, Room room) throws IOException {
        ensurePost(exchange);
        User user = requireUser(exchange);
        room.ensureHost(user.getId());
        room.startGame();
        HttpUtils.sendJson(exchange, 200, Map.of("room", room.toDto(dataStore)));
    }

    private void handleRoomMove(HttpExchange exchange, Room room) throws IOException {
        ensurePost(exchange);
        User user = requireUser(exchange);
        Map<String, Object> payload = readJsonObject(exchange);
        room.submitMove(user.getId(), payload);
        HttpUtils.sendJson(exchange, 200, Map.of("room", room.toDto(dataStore)));
    }

    private void ensurePost(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new HttpStatusException(405, "Method not allowed");
        }
    }

    private User requireUser(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        String token = headers.getFirst("X-Auth-Token");
        if (token == null || token.isBlank()) {
            throw new HttpStatusException(401, "Missing X-Auth-Token header");
        }
        return dataStore.findUserByToken(token);
    }

    private Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body.isBlank()) {
            return Map.of();
        }
        return JsonUtil.parseObject(body);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String normalizePath(String rawPath) {
        if (!rawPath.startsWith(API_ROOT)) {
            throw new HttpStatusException(404, "Invalid API root");
        }
        String path = rawPath.substring(API_ROOT.length());
        if (path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    private String asString(Object value, String field) {
        if (value instanceof String str && !str.isBlank()) {
            return str.trim();
        }
        throw new HttpStatusException(400, "Missing or invalid field: " + field);
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return false;
    }

    private Map<String, String> parseQuery(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            String key;
            String value;
            if (idx > 0) {
                key = decode(pair.substring(0, idx));
                value = decode(pair.substring(idx + 1));
            } else {
                key = decode(pair);
                value = "";
            }
            params.put(key, value);
        }
        return params;
    }

    private String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type,X-Auth-Token");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    }
}
