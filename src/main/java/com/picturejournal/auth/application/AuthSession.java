package com.picturejournal.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthSession(
        String token,
        UUID userId,
        Instant createdAt) {

    public AuthSession {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
