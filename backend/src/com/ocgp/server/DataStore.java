package com.ocgp.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore {
    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, User> usersByName = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final Map<String, Room> roomsById = new ConcurrentHashMap<>();

    public synchronized User register(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new HttpStatusException(400, "Username is required");
        }
        if (password == null || password.isBlank()) {
            throw new HttpStatusException(400, "Password is required");
        }
        if (usersByName.containsKey(username.toLowerCase())) {
            throw new HttpStatusException(409, "Username already exists");
        }
        User user = new User(username.trim(), password);
        usersById.put(user.getId(), user);
        usersByName.put(user.getUsername().toLowerCase(), user);
        return user;
    }

    public User authenticate(String username, String password) {
        if (username == null || password == null) {
            throw new HttpStatusException(400, "Missing credentials");
        }
        User user = usersByName.get(username.toLowerCase());
        if (user == null || !user.verifyPassword(password)) {
            throw new HttpStatusException(401, "Invalid credentials");
        }
        return user;
    }

    public Session createSession(String userId) {
        Session session = new Session(userId);
        sessionsByToken.put(session.getToken(), session);
        return session;
    }

    public User findUserByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new HttpStatusException(401, "Missing authentication token");
        }
        Session session = sessionsByToken.get(token);
        if (session == null) {
            throw new HttpStatusException(401, "Invalid session");
        }
        User user = usersById.get(session.getUserId());
        if (user == null) {
            throw new HttpStatusException(401, "Session user missing");
        }
        return user;
    }

    public User getUserById(String userId) {
        User user = usersById.get(userId);
        if (user == null) {
            throw new HttpStatusException(404, "User not found: " + userId);
        }
        return user;
    }

    public synchronized Room createRoom(User host, String name, GameType gameType, boolean privateRoom) {
        if (name == null || name.isBlank()) {
            throw new HttpStatusException(400, "Room name is required");
        }
        Room room = new Room(name.trim(), gameType, host.getId(), privateRoom, Instant.now());
        room.addPlayer(host.getId());
        roomsById.put(room.getId(), room);
        return room;
    }

    public List<Room> listRooms(Optional<GameType> filter) {
        List<Room> rooms = new ArrayList<>(roomsById.values());
        rooms.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        if (filter.isEmpty()) {
            return rooms;
        }
        GameType target = filter.get();
        List<Room> filtered = new ArrayList<>();
        for (Room room : rooms) {
            if (room.getGameType() == target) {
                filtered.add(room);
            }
        }
        return filtered;
    }

    public Room findRoom(String roomId) {
        Room room = roomsById.get(roomId);
        if (room == null) {
            throw new HttpStatusException(404, "Room not found");
        }
        return room;
    }

    public Map<String, Object> getGameCatalog() {
        Map<String, Object> games = new LinkedHashMap<>();
        for (GameType type : GameType.values()) {
            Map<String, Object> gameInfo = new LinkedHashMap<>();
            gameInfo.put("code", type.name());
            gameInfo.put("name", type.getDisplayName());
            gameInfo.put("description", type.getDescription());
            games.put(type.name(), gameInfo);
        }
        return games;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", List.copyOf(usersById.values()));
        result.put("sessions", List.copyOf(sessionsByToken.values()));
        result.put("rooms", List.copyOf(roomsById.values()));
        return result;
    }
}
