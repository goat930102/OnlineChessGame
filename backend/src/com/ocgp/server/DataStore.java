package com.ocgp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import java.util.logging.Level;
import java.util.logging.Logger;

public class DataStore implements AutoCloseable {
    private static final Duration EMPTY_ROOM_TTL = Duration.ofSeconds(30);
    private static final Logger LOGGER = Logger.getLogger(DataStore.class.getName());
    private static final String DEFAULT_DB = "out/data/ocgp.sqlite";

    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, User> usersByName = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final Map<String, Room> roomsById = new ConcurrentHashMap<>();
    private static final String CHAT_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS chat_messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                room_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(room_id) REFERENCES rooms(id) ON DELETE CASCADE,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """;

    private final Map<String, ScheduledFuture<?>> pendingRoomDeletions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("ocgp-room-janitor"));
    private WebSocketHub wsHub;

    private final Path dbPath;
    private final Connection conn;

    public DataStore() {
        this.dbPath = resolveDbPath();
        this.conn = initConnection(dbPath);
        initSchema();
        loadFromDb();
        scheduler.scheduleAtFixedRate(this::tickActiveRooms, 1, 1, TimeUnit.SECONDS);
    }

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
        persistUser(user);
        LOGGER.info(() -> "User registered: " + user.getUsername() + " (" + user.getId() + ")");
        return user;
    }

    public User authenticate(String username, String password) {
        if (username == null || password == null) {
            throw new HttpStatusException(400, "Missing credentials");
        }
        User user = usersByName.get(username.toLowerCase());
        if (user == null || !user.verifyPassword(password)) {
            LOGGER.warning(() -> "Invalid login attempt for user: " + username);
            throw new HttpStatusException(401, "Invalid credentials");
        }
        LOGGER.info(() -> "User login: " + user.getUsername() + " (" + user.getId() + ")");
        return user;
    }

    public synchronized Session createSession(String userId) {
        Session session = new Session(userId);
        sessionsByToken.put(session.getToken(), session);
        persistSession(session);
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
        String inviteCode = privateRoom ? generateInviteCode() : null;
        Room room = new Room(name.trim(), gameType, host.getId(), privateRoom, inviteCode, Instant.now());
        room.addPlayer(host.getId());
        roomsById.put(room.getId(), room);
        cancelScheduledRoomDeletion(room.getId());
        persistRoom(room);
        LOGGER.info(() -> String.format("Room created: %s (%s) by user %s", room.getName(), room.getId(), host.getId()));
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

    public synchronized void deleteRoom(String roomId) {
        roomsById.remove(roomId);
        cancelScheduledRoomDeletion(roomId);
        deleteRoomFromDb(roomId);
        persistAsync();
    }

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

    public void cancelScheduledRoomDeletion(String roomId) {
        ScheduledFuture<?> future = pendingRoomDeletions.remove(roomId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public void scheduleDisconnectCheck(String roomId, String userId, Instant expiry) {
        long delayMs = Math.max(0, Duration.between(Instant.now(), expiry).toMillis());
        scheduler.schedule(() -> {
            Room room = roomsById.get(roomId);
            if (room != null) {
                room.timeoutDisconnected(userId);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public void setWebSocketHub(WebSocketHub hub) {
        this.wsHub = hub;
    }

    private void tickActiveRooms() {
        List<Room> changed = new ArrayList<>();
        for (Room room : roomsById.values()) {
            if (room.checkTurnTimeout()) {
                changed.add(room);
                persistRoom(room);
            }
        }
        if (wsHub != null) {
            for (Room room : changed) {
                wsHub.broadcastRoom(room);
            }
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

    public synchronized void persistRoom(Room room) {
        try {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO rooms(id, name, game_type, host_user_id, private_room, invite_code, started, status, current_player_id, created_at, turn_deadline, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        name=excluded.name,
                        game_type=excluded.game_type,
                        host_user_id=excluded.host_user_id,
                        private_room=excluded.private_room,
                        invite_code=excluded.invite_code,
                        started=excluded.started,
                        status=excluded.status,
                        current_player_id=excluded.current_player_id,
                        turn_deadline=excluded.turn_deadline,
                        started_at=excluded.started_at
                    """)) {
                ps.setString(1, room.getId());
                ps.setString(2, room.getName());
                ps.setString(3, room.getGameType().name());
                ps.setString(4, room.getHostUserId());
                ps.setInt(5, room.isPrivateRoom() ? 1 : 0);
                ps.setString(6, room.getInviteCode());
                ps.setInt(7, room.isStarted() ? 1 : 0);
                ps.setString(8, room.getStatus());
                ps.setString(9, room.getCurrentPlayerId());
                ps.setString(10, room.getCreatedAt().toString());
                ps.setString(11, room.getTurnDeadline() != null ? room.getTurnDeadline().toString() : null);
                ps.setString(12, room.getStartedAt() != null ? room.getStartedAt().toString() : null);
                ps.executeUpdate();
            }

            try (PreparedStatement del = conn.prepareStatement("DELETE FROM room_players WHERE room_id = ?")) {
                del.setString(1, room.getId());
                del.executeUpdate();
            }
            List<String> players = room.getPlayerIds();
            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO room_players(room_id, user_id, position) VALUES (?, ?, ?)")) {
                for (int i = 0; i < players.size(); i++) {
                    ins.setString(1, room.getId());
                    ins.setString(2, players.get(i));
                    ins.setInt(3, i);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to persist room " + room.getId(), e);
        }
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", List.copyOf(usersById.values()));
        result.put("sessions", List.copyOf(sessionsByToken.values()));
        result.put("rooms", List.copyOf(roomsById.values()));
        return result;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            conn.close();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to close DB connection", e);
        }
    }

    private Path resolveDbPath() {
        String env = System.getenv("OCGP_DB_PATH");
        Path path = (env != null && !env.isBlank()) ? Path.of(env) : Path.of(DEFAULT_DB);
        Path abs = path.toAbsolutePath().normalize();
        try {
            Path parent = abs.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create DB directory", e);
        }
        return abs;
    }

    private Connection initConnection(Path path) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA foreign_keys=ON;");
            }
            LOGGER.info(() -> "SQLite DB at " + path);
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize database", e);
        }
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS users(
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        password_salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sessions(
                        token TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS rooms(
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        game_type TEXT NOT NULL,
                        host_user_id TEXT NOT NULL,
                        private_room INTEGER NOT NULL,
                        invite_code TEXT,
                        started INTEGER NOT NULL DEFAULT 0,
                        status TEXT,
                        current_player_id TEXT,
                        created_at TEXT NOT NULL,
                        turn_deadline TEXT,
                        started_at TEXT
                    )
                    """);
            try { st.execute("ALTER TABLE rooms ADD COLUMN invite_code TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE rooms ADD COLUMN turn_deadline TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE rooms ADD COLUMN started_at TEXT"); } catch (SQLException ignored) {}
            st.execute("""
                    CREATE TABLE IF NOT EXISTS room_players(
                        room_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(room_id, user_id),
                        FOREIGN KEY(room_id) REFERENCES rooms(id) ON DELETE CASCADE,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                    """);
            st.execute(CHAT_TABLE_SQL);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize schema", e);
        }
    }

    private void loadFromDb() {
        loadUsers();
        loadSessions();
        loadRooms();
        LOGGER.info(() -> String.format("Loaded from DB: users=%d rooms=%d", usersById.size(), roomsById.size()));
    }

    private void loadUsers() {
        String sql = "SELECT id, username, password_salt, password_hash, created_at FROM users";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("id");
                String username = rs.getString("username");
                String salt = rs.getString("password_salt");
                String hash = rs.getString("password_hash");
                Instant createdAt = Instant.parse(rs.getString("created_at"));
                User user = new User(id, username, salt, hash, createdAt);
                usersById.put(id, user);
                usersByName.put(username.toLowerCase(), user);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to load users", e);
        }
    }

    private void loadSessions() {
        String sql = "SELECT token, user_id, created_at FROM sessions";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String token = rs.getString("token");
                String userId = rs.getString("user_id");
                Instant createdAt = Instant.parse(rs.getString("created_at"));
                Session session = new Session(token, userId, createdAt);
                sessionsByToken.put(token, session);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to load sessions", e);
        }
    }

    private void loadRooms() {
        String sql = "SELECT id, name, game_type, host_user_id, private_room, invite_code, created_at, turn_deadline, started_at, started FROM rooms";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("id");
                String name = rs.getString("name");
                GameType gameType = GameType.fromString(rs.getString("game_type"));
                String hostUserId = rs.getString("host_user_id");
                boolean privateRoom = rs.getInt("private_room") == 1;
                String inviteCode = rs.getString("invite_code");
                Instant createdAt = Instant.parse(rs.getString("created_at"));
                boolean startedFlag = rs.getInt("started") == 1;
                Room room = new Room(id, name, gameType, hostUserId, privateRoom, inviteCode, createdAt);
                String td = rs.getString("turn_deadline");
                String sa = rs.getString("started_at");
                if (td != null && !td.isBlank()) {
                    room.setTurnDeadline(Instant.parse(td));
                }
                if (sa != null && !sa.isBlank()) {
                    room.setStartedAt(Instant.parse(sa));
                }
                room.setStarted(startedFlag);
                loadRoomPlayers(room);
                roomsById.put(id, room);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to load rooms", e);
        }
    }

    private void loadRoomPlayers(Room room) throws SQLException {
        String sql = "SELECT user_id FROM room_players WHERE room_id = ? ORDER BY position ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, room.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    room.addPlayer(rs.getString("user_id"));
                }
            }
        }
    }

    private void persistUser(User user) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO users(id, username, password_salt, password_hash, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(username) DO NOTHING
                """)) {
            ps.setString(1, user.getId());
            ps.setString(2, user.getUsername());
            ps.setString(3, user.getPasswordSalt());
            ps.setString(4, user.getPasswordHash());
            ps.setString(5, user.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new HttpStatusException(409, "Username already exists");
        }
    }

    private void persistSession(Session session) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO sessions(token, user_id, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT(token) DO NOTHING
                """)) {
            ps.setString(1, session.getToken());
            ps.setString(2, session.getUserId());
            ps.setString(3, session.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to persist session", e);
        }
    }

    private void deleteRoomFromDb(String roomId) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rooms WHERE id = ?")) {
            ps.setString(1, roomId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to delete room " + roomId, e);
        }
    }

    private String generateInviteCode() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int idx = (int) (Math.random() * alphabet.length());
            sb.append(alphabet.charAt(idx));
        }
        return sb.toString();
    }

    public void addChatMessage(String roomId, String userId, String content) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO chat_messages(room_id, user_id, content, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, roomId);
            ps.setString(2, userId);
            ps.setString(3, content);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to add chat message", e);
            throw new HttpStatusException(500, "Failed to save chat message");
        }
    }

    public List<Map<String, Object>> getChatMessages(String roomId, long sinceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT id, room_id, user_id, content, created_at FROM chat_messages WHERE room_id = ? AND id > ? ORDER BY id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setLong(2, sinceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", rs.getLong("id"));
                    entry.put("roomId", rs.getString("room_id"));
                    entry.put("userId", rs.getString("user_id"));
                    entry.put("content", rs.getString("content"));
                    entry.put("createdAt", rs.getString("created_at"));
                    result.add(entry);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to load chat messages", e);
            throw new HttpStatusException(500, "Failed to load chat messages");
        }
        return result;
    }

    /**
     * Legacy no-op: DB writes are already sync; kept for compatibility where async persistence was used.
     */
    private void persistAsync() {
        // No-op
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
