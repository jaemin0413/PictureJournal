package com.picturejournal.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MediaAsset(
        UUID mediaId,
        UUID uploaderUserId,
        String storageKey,
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
        Instant createdAt) {

    public MediaAsset {
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(uploaderUserId, "uploaderUserId must not be null");
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }
}
