package com.picturejournal.place.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ShareIntakeItem(
        UUID intakeId,
        UUID folderId,
        UUID receivedByUserId,
        String sourceApp,
        String platform,
        String receivedVia,
        String rawUrl,
        String rawTitle,
        String rawText,
        String normalizedUrl,
        ShareIntakeStatus status,
        String failureReason,
        UUID resolvedPlaceId,
        Instant receivedAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public ShareIntakeItem {
        Objects.requireNonNull(intakeId, "intakeId must not be null");
        Objects.requireNonNull(receivedByUserId, "receivedByUserId must not be null");
        Objects.requireNonNull(sourceApp, "sourceApp must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(receivedVia, "receivedVia must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static ShareIntakeItem create(
            UUID intakeId,
            UUID folderId,
            UUID actorId,
            String sourceApp,
            String platform,
            String receivedVia,
            String rawUrl,
            String rawTitle,
            String rawText,
            String normalizedUrl,
            ShareIntakeStatus status,
            String failureReason,
            Instant now) {
        return new ShareIntakeItem(intakeId, folderId, actorId, sourceApp, platform, receivedVia, rawUrl, rawTitle, rawText,
                normalizedUrl, status, failureReason, null, now, now, null);
    }

    public ShareIntakeItem saveDraft(Instant now) {
        return new ShareIntakeItem(intakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, ShareIntakeStatus.DRAFT, failureReason, resolvedPlaceId, receivedAt, now, resolvedAt);
    }

    public ShareIntakeItem resolve(UUID placeId, Instant now) {
        return new ShareIntakeItem(intakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, ShareIntakeStatus.RESOLVED, failureReason, placeId, receivedAt, now, now);
    }
}
