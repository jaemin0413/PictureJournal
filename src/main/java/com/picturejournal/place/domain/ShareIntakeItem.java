package com.picturejournal.place.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ShareIntakeItem(
        UUID intakeId,
        String clientIntakeId,
        UUID folderId,
        UUID receivedByUserId,
        String sourceApp,
        String platform,
        String receivedVia,
        String rawUrl,
        String rawTitle,
        String rawText,
        String normalizedUrl,
        String contentFingerprint,
        ShareIntakeStatus status,
        String failureReason,
        UUID resolvedPlaceId,
        Instant receivedAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public ShareIntakeItem {
        Objects.requireNonNull(intakeId, "intakeId must not be null");
        if (clientIntakeId == null || clientIntakeId.isBlank()) {
            clientIntakeId = intakeId.toString();
        }
        Objects.requireNonNull(clientIntakeId, "clientIntakeId must not be null");
        Objects.requireNonNull(receivedByUserId, "receivedByUserId must not be null");
        Objects.requireNonNull(sourceApp, "sourceApp must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(receivedVia, "receivedVia must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (status == ShareIntakeStatus.RESOLVED) {
            Objects.requireNonNull(resolvedPlaceId, "resolvedPlaceId must not be null for resolved intake");
            Objects.requireNonNull(resolvedAt, "resolvedAt must not be null for resolved intake");
        } else if (resolvedPlaceId != null || resolvedAt != null) {
            throw new IllegalArgumentException("unresolved intake must not reference a resolved place");
        }
    }

    public static ShareIntakeItem create(
            UUID intakeId,
            String clientIntakeId,
            UUID folderId,
            UUID actorId,
            String sourceApp,
            String platform,
            String receivedVia,
            String rawUrl,
            String rawTitle,
            String rawText,
            String normalizedUrl,
            String contentFingerprint,
            ShareIntakeStatus status,
            String failureReason,
            Instant now) {
        return new ShareIntakeItem(intakeId, clientIntakeId, folderId, actorId, sourceApp, platform, receivedVia, rawUrl, rawTitle, rawText,
                normalizedUrl, contentFingerprint, status, failureReason, null, now, now, null);
    }

    public ShareIntakeItem updateUnresolved(String rawUrl, String rawTitle, String rawText, String normalizedUrl, String failureReason, Instant now) {
        return new ShareIntakeItem(intakeId, clientIntakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, contentFingerprint, ShareIntakeStatus.NEEDS_MANUAL_FIX, failureReason, null, receivedAt, now, null);
    }

    public ShareIntakeItem resolve(UUID placeId, Instant now) {
        return new ShareIntakeItem(intakeId, clientIntakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, contentFingerprint, ShareIntakeStatus.RESOLVED, null, placeId, receivedAt, now, now);
    }
}
