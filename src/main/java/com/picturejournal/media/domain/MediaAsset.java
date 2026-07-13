package com.picturejournal.media.domain;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MediaAsset(
        UUID mediaId,
        UUID uploaderUserId,
        String storageKey,
        UUID intendedFolderId,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        Integer width,
        Integer height,
        String exifJson,
        Instant takenAt,
        String cameraMake,
        String cameraModel,
        Double gpsLatitude,
        Double gpsLongitude,
        String checksumSha256,
        Status status,
        UUID committedFolderId,
        UUID committedDiaryEntryId,
        Instant createdAt,
        Instant pendingExpiresAt,
        Instant committedAt) {

    public MediaAsset {
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(uploaderUserId, "uploaderUserId must not be null");
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        if (!storageKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || storageKey.contains("..")) {
            throw new IllegalArgumentException("storageKey contains unsupported characters");
        }
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(checksumSha256, "checksumSha256 must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(intendedFolderId, "intendedFolderId must not be null");
        Objects.requireNonNull(pendingExpiresAt, "pendingExpiresAt must not be null");
        if (status == Status.PENDING
                && (committedFolderId != null || committedDiaryEntryId != null || committedAt != null)) {
            throw new IllegalArgumentException("pending media must not contain commit metadata");
        }
        if (status == Status.COMMITTED) {
            Objects.requireNonNull(committedFolderId, "committedFolderId must not be null for committed media");
            Objects.requireNonNull(committedDiaryEntryId, "committedDiaryEntryId must not be null for committed media");
            Objects.requireNonNull(committedAt, "committedAt must not be null for committed media");
            if (!intendedFolderId.equals(committedFolderId)) {
                throw new IllegalArgumentException("committed folder must match intended folder");
            }
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }

    @JsonIgnore
    public boolean isPending() {
        return status == Status.PENDING;
    }

    public MediaAsset commit(UUID folderId, UUID diaryEntryId, Instant committedAt) {
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(diaryEntryId, "diaryEntryId must not be null");
        Objects.requireNonNull(committedAt, "committedAt must not be null");
        if (status != Status.PENDING) {
            throw new IllegalStateException("media asset is already committed");
        }
        if (!folderId.equals(intendedFolderId)) {
            throw new IllegalStateException("media asset was uploaded for a different folder");
        }
        if (!pendingExpiresAt.isAfter(committedAt)) {
            throw new IllegalStateException("media asset has expired");
        }
        return new MediaAsset(
                mediaId,
                uploaderUserId,
                storageKey,
                intendedFolderId,
                originalFilename,
                mimeType,
                sizeBytes,
                width,
                height,
                exifJson,
                takenAt,
                cameraMake,
                cameraModel,
                gpsLatitude,
                gpsLongitude,
                checksumSha256,
                Status.COMMITTED,
                folderId,
                diaryEntryId,
                createdAt,
                pendingExpiresAt,
                committedAt);
    }

    @JsonIgnore
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return status == Status.PENDING && !pendingExpiresAt.isAfter(now);
    }

    public enum Status {
        PENDING,
        COMMITTED
    }
}
