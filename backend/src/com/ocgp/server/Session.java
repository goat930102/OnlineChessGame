package com.ocgp.server;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class Session {
    private final String token;
    private final String userId;
    private final Instant createdAt;

    public Session(String userId) {
        this(UUID.randomUUID().toString(), userId, Instant.now());
    }

    public Session(String token, String userId, Instant createdAt) {
        this.token = token;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public String getToken() {
        return token;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> toDto() {
        return Map.of(
                "token", token,
                "userId", userId,
                "createdAt", createdAt.toString()
        );
    }
}
