package com.ocgp.server;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class User {
    private final String id;
    private final String username;
    private final String passwordHash;
    private final Instant createdAt;

    public User(String username, String password) {
        this(UUID.randomUUID().toString(), username, hashPassword(password), Instant.now());
    }

    public User(String id, String username, String passwordHash, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public boolean verifyPassword(String password) {
        return Objects.equals(passwordHash, hashPassword(password));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> toPublicDto() {
        return Map.of(
                "id", id,
                "username", username,
                "createdAt", createdAt.toString()
        );
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash password", e);
        }
    }
}
