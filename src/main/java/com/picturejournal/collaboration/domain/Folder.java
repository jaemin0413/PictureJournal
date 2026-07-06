package com.picturejournal.collaboration.domain;

import com.picturejournal.folder.domain.FolderType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Folder(
        UUID folderId,
        FolderType type,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public Folder {
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static Folder create(UUID folderId, FolderType type, String name, String description, Instant now) {
        return new Folder(folderId, type, name, description, now, now);
    }

    public Folder updateMetadata(String nextName, String nextDescription, Instant now) {
        return new Folder(folderId, type, nextName, nextDescription, createdAt, now);
    }
}
