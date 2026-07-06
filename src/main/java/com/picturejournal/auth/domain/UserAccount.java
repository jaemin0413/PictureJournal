package com.picturejournal.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserAccount(
        UUID userId,
        String email,
        String displayName,
        String passwordHash,
        Instant createdAt,
        Instant updatedAt) {

    public UserAccount {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
