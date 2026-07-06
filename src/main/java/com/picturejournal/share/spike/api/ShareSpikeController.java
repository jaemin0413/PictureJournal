package com.picturejournal.share.spike.api;

import com.picturejournal.share.spike.application.ShareSpikeDraft;
import com.picturejournal.share.spike.application.ShareSpikeService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/share-spike/drafts")
public class ShareSpikeController {

    private final ShareSpikeService shareSpikeService;

    public ShareSpikeController(ShareSpikeService shareSpikeService) {
        this.shareSpikeService = shareSpikeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareSpikeDraftResponse createDraft(@RequestBody CreateShareSpikeDraftRequest request) {
        ShareSpikeDraft draft = shareSpikeService.createDraft(new ShareSpikeService.CreateShareSpikeDraftCommand(
                request.sourceApp(),
                request.platform(),
                request.rawUrl(),
                request.rawTitle(),
                request.rawText(),
                request.actorId(),
                request.folderId()));
        return ShareSpikeDraftResponse.from(draft);
    }

    @GetMapping("/{draftId}")
    public ShareSpikeDraftResponse getDraft(@PathVariable UUID draftId) {
        return ShareSpikeDraftResponse.from(shareSpikeService.getDraft(draftId));
    }

    @PostMapping("/{draftId}/bind-auth")
    public ShareSpikeDraftResponse bindAuth(@PathVariable UUID draftId, @RequestBody BindAuthRequest request) {
        return ShareSpikeDraftResponse.from(shareSpikeService.bindActor(draftId, request.actorId()));
    }

    @PostMapping("/{draftId}/folder-selection")
    public ShareSpikeDraftResponse selectFolder(@PathVariable UUID draftId, @RequestBody SelectFolderRequest request) {
        return ShareSpikeDraftResponse.from(shareSpikeService.selectFolder(draftId, request.folderId()));
    }

    public record CreateShareSpikeDraftRequest(
            String sourceApp,
            String platform,
            String rawUrl,
            String rawTitle,
            String rawText,
            UUID actorId,
            UUID folderId) {
    }

    public record BindAuthRequest(UUID actorId) {
    }

    public record SelectFolderRequest(UUID folderId) {
    }

    public record ShareSpikeDraftResponse(
            UUID draftId,
            String status,
            UUID actorId,
            UUID folderId,
            String sourceApp,
            String platform,
            String rawUrl,
            String rawTitle,
            String rawText,
            Instant createdAt,
            Instant updatedAt) {

        static ShareSpikeDraftResponse from(ShareSpikeDraft draft) {
            return new ShareSpikeDraftResponse(
                    draft.draftId(),
                    draft.status().name(),
                    draft.actorId(),
                    draft.folderId(),
                    draft.payload().sourceApp(),
                    draft.payload().platform(),
                    draft.payload().rawUrl(),
                    draft.payload().rawTitle(),
                    draft.payload().rawText(),
                    draft.createdAt(),
                    draft.updatedAt());
        }
    }
}
