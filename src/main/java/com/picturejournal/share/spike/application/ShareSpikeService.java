package com.picturejournal.share.spike.application;

import com.picturejournal.share.spike.domain.SharePayload;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ShareSpikeService {

    private final ShareSpikeDraftStore store;
    private final Clock clock;

    @Autowired
    public ShareSpikeService(ShareSpikeDraftStore store) {
        this(store, Clock.systemUTC());
    }

    ShareSpikeService(ShareSpikeDraftStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ShareSpikeDraft createDraft(CreateShareSpikeDraftCommand command) {
        SharePayload payload = new SharePayload(
                normalizeRequired(command.sourceApp(), "sourceApp"),
                normalizeRequired(command.platform(), "platform"),
                normalizeOptional(command.rawUrl()),
                normalizeOptional(command.rawTitle()),
                normalizeOptional(command.rawText()));
        if (!payload.hasAnyContent()) {
            throw invalidArgument("At least one raw share payload field is required.");
        }
        if (command.actorId() == null && command.folderId() != null) {
            throw invalidArgument("folderId requires an authenticated actor.");
        }

        Instant now = Instant.now(clock);
        ShareSpikeDraft draft = ShareSpikeDraft.create(UUID.randomUUID(), command.actorId(), command.folderId(), payload, now);
        return store.save(draft);
    }

    public ShareSpikeDraft getDraft(UUID draftId) {
        return store.findById(draftId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Share spike draft " + draftId + " was not found."));
    }

    public ShareSpikeDraft bindActor(UUID draftId, UUID actorId) {
        if (actorId == null) {
            throw invalidArgument("actorId is required.");
        }
        ShareSpikeDraft draft = getDraft(draftId);
        return store.save(draft.bindActor(actorId, Instant.now(clock)));
    }

    public ShareSpikeDraft selectFolder(UUID draftId, UUID folderId) {
        if (folderId == null) {
            throw invalidArgument("folderId is required.");
        }
        ShareSpikeDraft draft = getDraft(draftId);
        if (draft.actorId() == null) {
            throw invalidArgument("folder selection requires an authenticated actor.");
        }
        return store.save(draft.selectFolder(folderId, Instant.now(clock)));
    }

    private DomainException invalidArgument(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw invalidArgument(fieldName + " is required.");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CreateShareSpikeDraftCommand(
            String sourceApp,
            String platform,
            String rawUrl,
            String rawTitle,
            String rawText,
            UUID actorId,
            UUID folderId) {
    }
}
