package com.picturejournal.diary.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.collaboration.application.FileCollaborationStore;
import com.picturejournal.collaboration.application.FolderCapabilityPolicyImpl;
import com.picturejournal.collaboration.domain.Folder;
import com.picturejournal.collaboration.domain.FolderMembership;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.diary.domain.DiaryEntry;
import com.picturejournal.media.application.ExifMetadataExtractor;
import com.picturejournal.media.application.FileMediaAssetStore;
import com.picturejournal.media.application.MediaAssetStore;
import com.picturejournal.media.application.MediaService;
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class DiaryServiceAtomicityTests {
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private FileCollaborationStore collaborationStore;
    private FileDiaryEntryStore diaryEntryStore;
    private FileMediaAssetStore mediaAssetStore;
    private UUID actorId;
    private UUID folderId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        collaborationStore = new FileCollaborationStore(objectMapper, tempDir.resolve("collaboration"));
        diaryEntryStore = new FileDiaryEntryStore(objectMapper, tempDir.resolve("diary"));
        mediaAssetStore = new FileMediaAssetStore(objectMapper, tempDir.resolve("media"));
        actorId = UUID.randomUUID();
        folderId = UUID.randomUUID();
        collaborationStore.saveFolder(Folder.create(folderId, FolderType.PHOTO_DIARY, "Diary", null, NOW));
        collaborationStore.saveMembership(FolderMembership.owner(folderId, actorId, NOW));
    }

    @Test
    void concurrentCreatesForTheSamePendingMediaLeaveOnlyTheConditionallyCommittedDiary() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MediaService mediaService = mediaService(mediaAssetStore, clock);
        MediaAsset pending = mediaService.uploadDirect(actorId, folderId, imageFile());
        CountDownLatch bothSavesCompleted = new CountDownLatch(2);
        DiaryService diaryService = diaryService(new SaveBarrierDiaryEntryStore(diaryEntryStore, bothSavesCompleted), mediaService, clock);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DiaryEntry> first = executor.submit(() -> createAfterStart(diaryService, pending.mediaId(), workersReady, start));
            Future<DiaryEntry> second = executor.submit(() -> createAfterStart(diaryService, pending.mediaId(), workersReady, start));
            assertTrue(workersReady.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Attempt> attempts = List.of(attempt(first), attempt(second));
            List<DiaryEntry> successes = attempts.stream().filter(Attempt::succeeded).map(Attempt::entry).toList();
            List<RuntimeException> failures = attempts.stream().filter(attempt -> !attempt.succeeded())
                    .map(Attempt::failure)
                    .toList();

            assertEquals(1, successes.size());
            assertEquals(1, failures.size());
            assertTrue(failures.getFirst() instanceof DomainException);
            DomainException conditionalCommitFailure = (DomainException) failures.getFirst();
            assertEquals(ErrorCode.INVALID_ARGUMENT, conditionalCommitFailure.getErrorCode());
            assertTrue(conditionalCommitFailure.getMessage().contains("already committed"));

            List<DiaryEntry> remainingEntries = diaryEntryStore.listByFolderId(folderId);
            assertEquals(1, remainingEntries.size());
            assertEquals(successes.getFirst().entryId(), remainingEntries.getFirst().entryId());
            MediaAsset committed = mediaAssetStore.findById(pending.mediaId()).orElseThrow();
            assertEquals(MediaAsset.Status.COMMITTED, committed.status());
            assertEquals(remainingEntries.getFirst().entryId(), committed.committedDiaryEntryId());
        }
    }

    @Test
    void commitFailureAfterDiarySaveDeletesDiaryAndLeavesObservablePendingMedia() {
        MutableClock clock = new MutableClock(NOW, ZoneOffset.UTC);
        FailingCommitMediaAssetStore faultingStore = new FailingCommitMediaAssetStore(
                mediaAssetStore, new IllegalStateException("media commit storage failure"));
        MediaService mediaService = mediaService(faultingStore, clock);
        MediaAsset pending = mediaService.uploadDirect(actorId, folderId, imageFile());
        clock.setInstant(NOW.plusSeconds(1));
        DiaryService diaryService = diaryService(diaryEntryStore, mediaService, clock);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> diaryService.createEntry(actorId, folderId, createCommand(pending.mediaId())));

        assertEquals("media commit storage failure", failure.getMessage());
        assertEquals(1, faultingStore.commitAttempts);
        assertTrue(diaryEntryStore.listByFolderId(folderId).isEmpty());
        MediaAsset persisted = mediaAssetStore.findById(pending.mediaId()).orElseThrow();
        assertEquals(MediaAsset.Status.PENDING, persisted.status());
        assertNull(persisted.committedDiaryEntryId());
    }

    @Test
    void nonFiniteCoordinatesAreRejectedWithoutMutatingDiaryOrMediaState() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MediaService mediaService = mediaService(mediaAssetStore, clock);
        MediaAsset pending = mediaService.uploadDirect(actorId, folderId, imageFile());
        DiaryService diaryService = diaryService(diaryEntryStore, mediaService, clock);

        DomainException createFailure = assertThrows(
                DomainException.class,
                () -> diaryService.createEntry(
                        actorId,
                        folderId,
                        new DiaryService.CreateDiaryEntryCommand(
                                pending.mediaId(), "Title", null, null, Double.NaN, 127.0, NOW, List.of())));
        assertEquals(ErrorCode.INVALID_ARGUMENT, createFailure.getErrorCode());
        assertTrue(diaryEntryStore.listByFolderId(folderId).isEmpty());
        assertEquals(MediaAsset.Status.PENDING, mediaAssetStore.findById(pending.mediaId()).orElseThrow().status());

        DiaryEntry created = diaryService.createEntry(actorId, folderId, createCommand(pending.mediaId()));
        DomainException updateFailure = assertThrows(
                DomainException.class,
                () -> diaryService.updateEntry(
                        actorId,
                        created.entryId(),
                        new DiaryService.UpdateDiaryEntryCommand(null, null, null, null, Double.NaN, null, null)));
        assertEquals(ErrorCode.INVALID_ARGUMENT, updateFailure.getErrorCode());
        DiaryEntry unchanged = diaryEntryStore.findById(created.entryId()).orElseThrow();
        assertEquals(37.0, unchanged.latitude());
        assertEquals(127.0, unchanged.longitude());
    }

    private DiaryService diaryService(DiaryEntryStore store, MediaService mediaService, Clock clock) {
        return new DiaryService(
                store,
                collaborationStore,
                new FolderCapabilityPolicyImpl(collaborationStore),
                mediaService,
                clock);
    }

    private MediaService mediaService(MediaAssetStore store, Clock clock) {
        return new MediaService(store, new ExifMetadataExtractor(objectMapper), collaborationStore, clock);
    }

    private DiaryEntry createAfterStart(
            DiaryService diaryService, UUID mediaId, CountDownLatch workersReady, CountDownLatch start) throws InterruptedException {
        workersReady.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return diaryService.createEntry(actorId, folderId, createCommand(mediaId));
    }

    private Attempt attempt(Future<DiaryEntry> future) throws InterruptedException {
        try {
            return Attempt.success(future.get(5, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                return Attempt.failure(runtimeException);
            }
            throw new AssertionError(cause);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new AssertionError("Concurrent diary creation did not complete", exception);
        }
    }

    private DiaryService.CreateDiaryEntryCommand createCommand(UUID mediaId) {
        return new DiaryService.CreateDiaryEntryCommand(mediaId, "Title", "Body", "Place", 37.0, 127.0, NOW, List.of("tag"));
    }

    private MockMultipartFile imageFile() {
        return new MockMultipartFile("file", "photo.png", "image/png", TINY_PNG);
    }

    private record Attempt(DiaryEntry entry, RuntimeException failure) {
        private static Attempt success(DiaryEntry entry) {
            return new Attempt(entry, null);
        }

        private static Attempt failure(RuntimeException failure) {
            return new Attempt(null, failure);
        }

        private boolean succeeded() {
            return entry != null;
        }
    }

    private static final class SaveBarrierDiaryEntryStore implements DiaryEntryStore {
        private final DiaryEntryStore delegate;
        private final CountDownLatch bothSavesCompleted;

        private SaveBarrierDiaryEntryStore(DiaryEntryStore delegate, CountDownLatch bothSavesCompleted) {
            this.delegate = delegate;
            this.bothSavesCompleted = bothSavesCompleted;
        }

        @Override
        public DiaryEntry save(DiaryEntry diaryEntry) {
            DiaryEntry saved = delegate.save(diaryEntry);
            bothSavesCompleted.countDown();
            try {
                if (!bothSavesCompleted.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Both diary saves did not complete");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while coordinating diary saves", exception);
            }
            return saved;
        }

        @Override
        public Optional<DiaryEntry> findById(UUID entryId) {
            return delegate.findById(entryId);
        }

        @Override
        public List<DiaryEntry> listByFolderId(UUID folderId) {
            return delegate.listByFolderId(folderId);
        }

        @Override
        public void delete(UUID entryId) {
            delegate.delete(entryId);
        }
    }

    private static final class FailingCommitMediaAssetStore implements MediaAssetStore {
        private final MediaAssetStore delegate;
        private final RuntimeException failure;
        private int commitAttempts;

        private FailingCommitMediaAssetStore(MediaAssetStore delegate, RuntimeException failure) {
            this.delegate = delegate;
            this.failure = failure;
        }

        @Override
        public MediaAsset saveWithBlob(MediaAsset mediaAsset, byte[] blobBytes) {
            return delegate.saveWithBlob(mediaAsset, blobBytes);
        }

        @Override
        public MediaAsset commitPending(
                UUID mediaId, UUID uploaderUserId, UUID folderId, UUID diaryEntryId, Instant committedAt) {
            commitAttempts++;
            throw failure;
        }

        @Override
        public int cleanupExpiredPending(Instant now) {
            return delegate.cleanupExpiredPending(now);
        }

        @Override
        public Optional<MediaAssetStore.Metadata> findMetadata(UUID mediaId) {
            return delegate.findMetadata(mediaId);
        }

        @Override
        public MediaAssetStore.Snapshot readSnapshot(MediaAssetStore.Metadata metadata) {
            return delegate.readSnapshot(metadata);
        }

        @Override
        public Optional<MediaAsset> findById(UUID mediaId) {
            return delegate.findById(mediaId);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }
    }
}
