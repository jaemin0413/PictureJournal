package com.picturejournal.share.spike.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.share.spike.domain.ShareSpikeStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShareSpikeServiceTests {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-05T16:00:00Z");

    @TempDir
    Path tempDir;

    private ShareSpikeService shareSpikeService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileShareSpikeDraftStore store = new FileShareSpikeDraftStore(objectMapper, tempDir);
        shareSpikeService = new ShareSpikeService(store, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void authenticatedWarmStartCreatesReadyForReviewDraft() {
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        ShareSpikeDraft draft = shareSpikeService.createDraft(new ShareSpikeService.CreateShareSpikeDraftCommand(
                "instagram",
                "IOS",
                "https://instagram.com/reel/123",
                "Trip reel",
                "caption",
                actorId,
                folderId));

        assertEquals(ShareSpikeStatus.READY_FOR_REVIEW, draft.status());
        assertEquals(actorId, draft.actorId());
        assertEquals(folderId, draft.folderId());
        assertEquals(FIXED_NOW, draft.createdAt());
        assertEquals(FIXED_NOW, draft.updatedAt());
    }

    @Test
    void coldStartRecoveryReloadsPersistedDraft() {
        ShareSpikeDraft created = shareSpikeService.createDraft(new ShareSpikeService.CreateShareSpikeDraftCommand(
                "instagram",
                "ANDROID",
                "https://instagram.com/reel/abc",
                null,
                null,
                null,
                null));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ShareSpikeService reloadedService = new ShareSpikeService(
                new FileShareSpikeDraftStore(objectMapper, tempDir),
                Clock.fixed(FIXED_NOW.plusSeconds(30), ZoneOffset.UTC));

        ShareSpikeDraft recovered = reloadedService.getDraft(created.draftId());

        assertEquals(created.draftId(), recovered.draftId());
        assertEquals(ShareSpikeStatus.PENDING_AUTH, recovered.status());
        assertEquals("https://instagram.com/reel/abc", recovered.payload().rawUrl());
    }

    @Test
    void unauthenticatedDraftCanBindActorLater() {
        ShareSpikeDraft created = shareSpikeService.createDraft(new ShareSpikeService.CreateShareSpikeDraftCommand(
                "instagram",
                "IOS",
                null,
                "Shared title",
                null,
                null,
                null));
        UUID actorId = UUID.randomUUID();

        ShareSpikeDraft rebound = shareSpikeService.bindActor(created.draftId(), actorId);

        assertEquals(ShareSpikeStatus.PENDING_AUTH, created.status());
        assertNull(created.actorId());
        assertEquals(ShareSpikeStatus.AWAITING_FOLDER_SELECTION, rebound.status());
        assertEquals(actorId, rebound.actorId());
    }

    @Test
    void folderSelectionPersistsAcrossReload() {
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        ShareSpikeDraft created = shareSpikeService.createDraft(new ShareSpikeService.CreateShareSpikeDraftCommand(
                "instagram",
                "ANDROID",
                null,
                "Shared title",
                "shared body",
                actorId,
                null));

        ShareSpikeDraft updated = shareSpikeService.selectFolder(created.draftId(), folderId);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ShareSpikeService reloadedService = new ShareSpikeService(
                new FileShareSpikeDraftStore(objectMapper, tempDir),
                Clock.fixed(FIXED_NOW.plusSeconds(30), ZoneOffset.UTC));
        ShareSpikeDraft recovered = reloadedService.getDraft(created.draftId());

        assertEquals(ShareSpikeStatus.AWAITING_FOLDER_SELECTION, created.status());
        assertEquals(ShareSpikeStatus.READY_FOR_REVIEW, updated.status());
        assertEquals(ShareSpikeStatus.READY_FOR_REVIEW, recovered.status());
        assertEquals(folderId, recovered.folderId());
    }
}
