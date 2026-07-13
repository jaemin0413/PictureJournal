package com.picturejournal.place.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        String createIdentity,
        List<CreateReceipt> createReceipts,
        String resolutionIdentity,
        ShareIntakeStatus status,
        String failureReason,
        UUID resolvedPlaceId,
        Instant receivedAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public ShareIntakeItem {
        Objects.requireNonNull(intakeId, "intakeId must not be null");
        if (clientIntakeId == null || clientIntakeId.isBlank()) {
            throw new IllegalArgumentException("clientIntakeId must not be blank");
        }
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(receivedByUserId, "receivedByUserId must not be null");
        Objects.requireNonNull(sourceApp, "sourceApp must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(receivedVia, "receivedVia must not be null");
        if (contentFingerprint == null || !contentFingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("contentFingerprint must be exactly 64 hexadecimal characters");
        }
        if (createIdentity == null || createIdentity.isBlank()) {
            throw new IllegalArgumentException("createIdentity must not be blank");
        }
        createReceipts = createReceipts == null ? List.of() : List.copyOf(createReceipts);
        if (createReceipts.stream().map(CreateReceipt::clientIntakeId).distinct().count() != createReceipts.size()) {
            throw new IllegalArgumentException("create receipt clientIntakeIds must be unique");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (status == ShareIntakeStatus.RESOLVED) {
            Objects.requireNonNull(resolvedPlaceId, "resolvedPlaceId must not be null for resolved intake");
            Objects.requireNonNull(resolvedAt, "resolvedAt must not be null for resolved intake");
            Objects.requireNonNull(resolutionIdentity, "resolutionIdentity must not be null for resolved intake");
            if (resolutionIdentity.isBlank()) {
                throw new IllegalArgumentException("resolutionIdentity must not be blank for resolved intake");
            }
        } else if (resolvedPlaceId != null || resolvedAt != null || resolutionIdentity != null) {
            throw new IllegalArgumentException("unresolved intake must not retain resolution state");
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
            String createIdentity,
            String failureReason,
            Instant now) {
        return new ShareIntakeItem(intakeId, clientIntakeId, folderId, actorId, sourceApp, platform, receivedVia, rawUrl, rawTitle, rawText,
                normalizedUrl, contentFingerprint, createIdentity, List.of(), null, ShareIntakeStatus.NEEDS_MANUAL_FIX, failureReason, null, now, now, null);
    }

    public ShareIntakeItem withCreateReceipt(CreateReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        if (!folderId.equals(receipt.folderId()) || !receivedByUserId.equals(receipt.actorId())) {
            throw new IllegalArgumentException("create receipt must belong to the intake actor and folder");
        }
        CreateReceipt existing = createReceipts.stream()
                .filter(item -> item.clientIntakeId().equals(receipt.clientIntakeId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!existing.createIdentity().equals(receipt.createIdentity())) {
                throw new IllegalStateException("clientIntakeId is already used for a different share intake request");
            }
            return this;
        }
        List<CreateReceipt> receipts = new ArrayList<>(createReceipts);
        receipts.add(receipt);
        return copy(receipts, resolutionIdentity, status, failureReason, resolvedPlaceId, updatedAt, resolvedAt);
    }

    public ShareIntakeItem updateUnresolved(String rawUrl, String rawTitle, String rawText, String normalizedUrl, String failureReason, Instant now) {
        if (status == ShareIntakeStatus.RESOLVED) {
            throw new IllegalStateException("A resolved share intake cannot be updated.");
        }
        return new ShareIntakeItem(intakeId, clientIntakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, contentFingerprint, createIdentity, createReceipts, null, ShareIntakeStatus.NEEDS_MANUAL_FIX, failureReason, null,
                receivedAt, now, null);
    }

    public ShareIntakeItem resolve(UUID placeId, String nextResolutionIdentity, Instant now) {
        Objects.requireNonNull(placeId, "placeId must not be null");
        Objects.requireNonNull(nextResolutionIdentity, "nextResolutionIdentity must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (status == ShareIntakeStatus.RESOLVED) {
            if (!resolvedPlaceId.equals(placeId) || !resolutionIdentity.equals(nextResolutionIdentity)) {
                throw new IllegalStateException("A resolved share intake cannot be rebound or resolved differently.");
            }
            return this;
        }
        return copy(createReceipts, nextResolutionIdentity, ShareIntakeStatus.RESOLVED, null, placeId, now, now);
    }

    private ShareIntakeItem copy(
            List<CreateReceipt> receipts,
            String nextResolutionIdentity,
            ShareIntakeStatus nextStatus,
            String nextFailureReason,
            UUID nextResolvedPlaceId,
            Instant nextUpdatedAt,
            Instant nextResolvedAt) {
        return new ShareIntakeItem(intakeId, clientIntakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, contentFingerprint, createIdentity, receipts, nextResolutionIdentity, nextStatus, nextFailureReason,
                nextResolvedPlaceId, receivedAt, nextUpdatedAt, nextResolvedAt);
    }

    public record CreateReceipt(UUID actorId, UUID folderId, String clientIntakeId, String createIdentity) {
        public CreateReceipt {
            Objects.requireNonNull(actorId, "actorId must not be null");
            Objects.requireNonNull(folderId, "folderId must not be null");
            if (clientIntakeId == null || clientIntakeId.isBlank()) {
                throw new IllegalArgumentException("clientIntakeId must not be blank");
            }
            if (createIdentity == null || createIdentity.isBlank()) {
                throw new IllegalArgumentException("createIdentity must not be blank");
            }
        }
    }
}
