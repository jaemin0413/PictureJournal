package com.picturejournal.share.spike.application;

import com.picturejournal.share.spike.domain.SharePayload;
import com.picturejournal.share.spike.domain.ShareSpikeStatus;
import java.time.Instant;
import java.util.UUID;

public record ShareSpikeDraft(
        UUID draftId,
        ShareSpikeStatus status,
        UUID actorId,
        UUID folderId,
        SharePayload payload,
        Instant createdAt,
        Instant updatedAt) {

    public static ShareSpikeDraft create(UUID draftId, UUID actorId, UUID folderId, SharePayload payload, Instant now) {
        return new ShareSpikeDraft(
                draftId,
                resolveStatus(actorId, folderId),
                actorId,
                folderId,
                payload,
                now,
                now);
    }

    public ShareSpikeDraft bindActor(UUID nextActorId, Instant now) {
        return new ShareSpikeDraft(
                draftId,
                resolveStatus(nextActorId, folderId),
                nextActorId,
                folderId,
                payload,
                createdAt,
                now);
    }

    public ShareSpikeDraft selectFolder(UUID nextFolderId, Instant now) {
        return new ShareSpikeDraft(
                draftId,
                resolveStatus(actorId, nextFolderId),
                actorId,
                nextFolderId,
                payload,
                createdAt,
                now);
    }

    private static ShareSpikeStatus resolveStatus(UUID actorId, UUID folderId) {
        if (actorId == null) {
            return ShareSpikeStatus.PENDING_AUTH;
        }
        if (folderId == null) {
            return ShareSpikeStatus.AWAITING_FOLDER_SELECTION;
        }
        return ShareSpikeStatus.READY_FOR_REVIEW;
    }
}
