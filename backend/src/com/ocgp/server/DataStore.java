package com.ocgp.server;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class DataStore implements AutoCloseable {
    private static final Duration EMPTY_ROOM_TTL = Duration.ofSeconds(30);

    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, User> usersByName = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final Map<String, Room> roomsById = new ConcurrentHashMap<>();

    private final Map<String, ScheduledFuture<?>> pendingRoomDeletions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("ocgp-room-janitor"));

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
        // 新建房間不應該有刪除排程，但保險起見取消一次
        cancelScheduledRoomDeletion(room.getId());
        return room;
    }

    public List<Room> listRooms(Optional<GameType> filter) {
        List<Room> rooms = new ArrayList<>(roomsById.values());
        rooms.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));

        // 只回傳公開房間
        List<Room> publicRooms = new ArrayList<>();
        for (Room room : rooms) {
            if (!room.isPrivateRoom()) {
                publicRooms.add(room);
            }
        }

        if (filter.isEmpty()) {
            return publicRooms;
        }

        GameType target = filter.get();
        List<Room> filtered = new ArrayList<>();
        for (Room room : publicRooms) {
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

    /**
     * 立即刪除房間（用於真正要刪除的情境）。
     * 若存在延遲刪除排程，也會一併取消，避免執行緒殘留。
     */
    public synchronized void deleteRoom(String roomId) {
        roomsById.remove(roomId);
        cancelScheduledRoomDeletion(roomId);
    }

    /**
     * 當房間變成空房（0 人）時呼叫：排程 30 秒後刪除。
     * 若期間有人重新加入，應呼叫 cancelScheduledRoomDeletion() 取消。
     */
    public void scheduleRoomDeletionIfEmpty(String roomId) {
        Room room = roomsById.get(roomId);
        if (room == null) {
            return;
        }
        if (room.getPlayerCount() != 0) {
            return;
        }

        pendingRoomDeletions.compute(roomId, (id, existing) -> {
            if (existing != null && !existing.isDone() && !existing.isCancelled()) {
                return existing;
            }
            return scheduler.schedule(() -> {
                try {
                    Room current = roomsById.get(id);
                    if (current != null && current.getPlayerCount() == 0) {
                        deleteRoom(id);
                    }
                } finally {
                    pendingRoomDeletions.remove(id);
                }
            }, EMPTY_ROOM_TTL.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    /**
     * 有玩家重新加入房間時呼叫：取消先前的延遲刪除排程。
     */
    public void cancelScheduledRoomDeletion(String roomId) {
        ScheduledFuture<?> future = pendingRoomDeletions.remove(roomId);
        if (future != null) {
            future.cancel(false);
        }
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

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private int index = 0;

        private DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName(namePrefix + "-" + (++index));
            return t;
        }
    }
}
