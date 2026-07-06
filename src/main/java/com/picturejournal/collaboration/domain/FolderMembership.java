package com.picturejournal.collaboration.domain;

import com.picturejournal.folder.domain.FolderRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FolderMembership(
        UUID folderId,
        UUID actorId,
        FolderRole role,
        Instant createdAt) {

    public FolderMembership {
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static FolderMembership owner(UUID folderId, UUID actorId, Instant now) {
        return new FolderMembership(folderId, actorId, FolderRole.OWNER, now);
    }
}
