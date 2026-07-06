package com.picturejournal.collaboration.domain;

import com.picturejournal.folder.domain.FolderRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FolderInvite(
        UUID inviteId,
        UUID folderId,
        String token,
        FolderRole role,
        FolderInviteStatus status,
        UUID createdBy,
        UUID acceptedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant acceptedAt) {

    public FolderInvite {
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static FolderInvite create(UUID inviteId, UUID folderId, String token, FolderRole role, UUID createdBy, Instant now) {
        return new FolderInvite(inviteId, folderId, token, role, FolderInviteStatus.PENDING, createdBy, null, now, now, null);
    }

    public FolderInvite accept(UUID actorId, Instant now) {
        return new FolderInvite(inviteId, folderId, token, role, FolderInviteStatus.ACCEPTED, createdBy, actorId, createdAt, now, now);
    }
}
