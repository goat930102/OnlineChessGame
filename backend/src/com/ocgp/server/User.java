package com.ocgp.server;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class User {
    private final String id;
    private final String username;
    private final String passwordSalt;
    private final String passwordHash;
    private final Instant createdAt;

    public User(String username, String password) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.passwordSalt = generateSalt();
        this.passwordHash = hashPassword(password, this.passwordSalt);
        this.createdAt = Instant.now();
    }

    public User(String id, String username, String passwordSalt, String passwordHash, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordSalt = passwordSalt;
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
        return Objects.equals(passwordHash, hashPassword(password, passwordSalt));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Map<String, Object> toPublicDto() {
        return Map.of(
                "id", id,
                "username", username,
                "createdAt", createdAt.toString()
        );
    }

    private static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + ":" + password).getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash password", e);
        }
    }

    private static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

}
