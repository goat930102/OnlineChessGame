package com.ocgp.server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class WebSocketHub extends WebSocketServer {
    private static final Logger LOGGER = Logger.getLogger(WebSocketHub.class.getName());

    private final DataStore dataStore;
    private final Map<String, Set<WebSocket>> roomSockets = new ConcurrentHashMap<>();

    public WebSocketHub(int port, DataStore dataStore) {
        super(new InetSocketAddress(port));
        this.dataStore = dataStore;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Map<String, String> params = parseQuery(handshake.getResourceDescriptor());
        String roomId = params.get("roomId");
        String token = params.get("token");
        if (roomId == null || token == null) {
            conn.close(1008, "Missing roomId or token");
            return;
        }
        try {
            User user = dataStore.findUserByToken(token);
            Room room = dataStore.findRoom(roomId);
            room.ensurePlayer(user.getId());
            roomSockets.computeIfAbsent(roomId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(conn);
            LOGGER.info(() -> "WS connected: user " + user.getId() + " room " + roomId);
        } catch (HttpStatusException ex) {
            conn.close(1008, ex.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        roomSockets.values().forEach(set -> set.remove(conn));
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // No-op: server only pushes updates
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.warning("WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        LOGGER.info("WebSocket server started");
    }

    public void broadcastRoom(Room room) {
        String json = JsonUtil.stringify(Map.of("type", "roomUpdate", "room", room.toDto(dataStore)));
        broadcastToRoom(room.getId(), json);
    }

    public void broadcastChat(String roomId, Map<String, Object> message) {
        String json = JsonUtil.stringify(Map.of("type", "chatMessage", "message", message));
        broadcastToRoom(roomId, json);
    }

    private void broadcastToRoom(String roomId, String payload) {
        Set<WebSocket> targets = roomSockets.get(roomId);
        if (targets == null) return;
        for (WebSocket ws : targets) {
            ws.send(payload);
        }
    }

    private Map<String, String> parseQuery(String resource) {
        Map<String, String> map = new HashMap<>();
        try {
            URI uri = new URI(resource);
            String query = uri.getQuery();
            if (query == null || query.isBlank()) {
                return map;
            }
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    map.put(k, v);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }
}
