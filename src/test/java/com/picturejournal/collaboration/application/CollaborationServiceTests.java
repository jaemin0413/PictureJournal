package com.picturejournal.collaboration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.collaboration.domain.FolderMembership;
import com.picturejournal.folder.domain.FolderRole;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollaborationServiceTests {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-06T05:00:00Z");

    @TempDir
    Path tempDir;

    private FileCollaborationStore collaborationStore;
    private CollaborationService collaborationService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        collaborationStore = new FileCollaborationStore(objectMapper, tempDir.resolve("collaboration"));
        collaborationService = new CollaborationService(
                collaborationStore,
                new FolderCapabilityPolicyImpl(collaborationStore),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void listFoldersFailsLoudlyOnOrphanedMembership() {
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        collaborationStore.saveMembership(new FolderMembership(folderId, actorId, FolderRole.OWNER, FIXED_NOW));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> collaborationService.listFolders(actorId, null));

        assertEquals(
                "Membership " + folderId + "/" + actorId + " references a missing folder.",
                exception.getMessage());
    }

    @Test
    void createFolderRequiresExplicitFolderType() {
        UUID actorId = UUID.randomUUID();

        DomainException exception = assertThrows(
                DomainException.class,
                () -> collaborationService.createFolder(actorId, new CollaborationService.CreateFolderCommand(null, "Name", null)));

        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
        assertEquals("type is required.", exception.getMessage());
    }

    @Test
    void createFolderPersistsRequestedType() {
        UUID actorId = UUID.randomUUID();

        CollaborationService.FolderView folderView = collaborationService.createFolder(
                actorId,
                new CollaborationService.CreateFolderCommand(FolderType.REELS_PLACE, "Wish list", "saved"));

        assertEquals(FolderType.REELS_PLACE, folderView.type());
        assertEquals(FolderRole.OWNER, folderView.role());
    }

    @Test
    void acceptInviteRequiresInviterToRetainWriteCapability() {
        UUID ownerId = UUID.randomUUID();
        UUID inviteeId = UUID.randomUUID();
        CollaborationService.FolderView folder = collaborationService.createFolder(
                ownerId,
                new CollaborationService.CreateFolderCommand(FolderType.PHOTO_DIARY, "Diary", null));

        collaborationStore.saveInvite(com.picturejournal.collaboration.domain.FolderInvite.create(
                UUID.randomUUID(),
                folder.folderId(),
                "invite123",
                FolderRole.VIEWER,
                ownerId,
                FIXED_NOW));
        collaborationStore.saveMembership(new FolderMembership(folder.folderId(), ownerId, FolderRole.VIEWER, FIXED_NOW));

        DomainException exception = assertThrows(
                DomainException.class,
                () -> collaborationService.acceptInvite(inviteeId, "invite123"));

        assertEquals(ErrorCode.FOLDER_WRITE_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void inviteAcceptanceIsSingleUseUnderConcurrentCalls() throws Exception {
        UUID ownerId = UUID.randomUUID();
        CollaborationService.FolderView folder = collaborationService.createFolder(
                ownerId,
                new CollaborationService.CreateFolderCommand(FolderType.PHOTO_DIARY, "Diary", null));
        collaborationStore.saveInvite(com.picturejournal.collaboration.domain.FolderInvite.create(
                UUID.randomUUID(),
                folder.folderId(),
                "invite456",
                FolderRole.VIEWER,
                ownerId,
                FIXED_NOW));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Object>> tasks = List.of(
                    () -> awaitAndRun(start, () -> collaborationService.acceptInvite(UUID.randomUUID(), "invite456")),
                    () -> awaitAndRun(start, () -> collaborationService.acceptInvite(UUID.randomUUID(), "invite456")));
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();

            int successCount = 0;
            int conflictCount = 0;
            for (Future<Object> future : futures) {
                try {
                    future.get();
                    successCount++;
                } catch (Exception exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof DomainException domainException && domainException.getErrorCode() == ErrorCode.CONFLICT) {
                        conflictCount++;
                    } else {
                        throw exception;
                    }
                }
            }

            assertEquals(1, successCount);
            assertEquals(1, conflictCount);
        }
    }

    private Object awaitAndRun(CountDownLatch start, Callable<?> callable) throws Exception {
        start.await();
        return callable.call();
    }
}
