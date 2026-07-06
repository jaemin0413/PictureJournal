package com.picturejournal.diary.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DiaryEntry(
        UUID entryId,
        UUID folderId,
        UUID authorUserId,
        UUID mediaId,
        String title,
        String body,
        String placeName,
        double latitude,
        double longitude,
        Instant capturedAt,
        String visibilityMode,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt) {

    public DiaryEntry {
        Objects.requireNonNull(entryId, "entryId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(authorUserId, "authorUserId must not be null");
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(visibilityMode, "visibilityMode must not be null");
        Objects.requireNonNull(tags, "tags must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        tags = List.copyOf(tags);
    }

    public static DiaryEntry create(
            UUID entryId,
            UUID folderId,
            UUID authorUserId,
            UUID mediaId,
            String title,
            String body,
            String placeName,
            double latitude,
            double longitude,
            Instant capturedAt,
            List<String> tags,
            Instant now) {
        return new DiaryEntry(entryId, folderId, authorUserId, mediaId, title, body, placeName, latitude, longitude,
                capturedAt, "folder_members", tags, now, now);
    }

    public DiaryEntry update(
            String nextTitle,
            String nextBody,
            String nextPlaceName,
            double nextLatitude,
            double nextLongitude,
            Instant nextCapturedAt,
            List<String> nextTags,
            Instant now) {
        return new DiaryEntry(entryId, folderId, authorUserId, mediaId, nextTitle, nextBody, nextPlaceName,
                nextLatitude, nextLongitude, nextCapturedAt, visibilityMode, nextTags, createdAt, now);
    }
}
