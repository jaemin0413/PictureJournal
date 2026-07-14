package com.picturejournal.media.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.collaboration.application.FileCollaborationStore;
import com.picturejournal.collaboration.domain.Folder;
import com.picturejournal.collaboration.domain.FolderMembership;
import com.picturejournal.folder.application.FolderCapabilityPolicy;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.DomainException;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class MediaServiceTests {
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");

    @TempDir
    Path tempDir;

    private MediaService mediaService;
    private UUID ownerId;
    private UUID folderId;
    private FileCollaborationStore collaborationStore;
    private RecordingFolderCapabilityPolicy folderCapabilityPolicy;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        collaborationStore = new FileCollaborationStore(objectMapper, tempDir.resolve("collaboration"));
        ownerId = UUID.randomUUID();
        folderId = UUID.randomUUID();
        collaborationStore.saveFolder(Folder.create(folderId, FolderType.PHOTO_DIARY, "Diary", null, NOW));
        collaborationStore.saveMembership(FolderMembership.owner(folderId, ownerId, NOW));
        folderCapabilityPolicy = new RecordingFolderCapabilityPolicy();
        mediaService = new MediaService(
                new FileMediaAssetStore(objectMapper, tempDir.resolve("media")),
                new ExifMetadataExtractor(objectMapper),
                collaborationStore,
                folderCapabilityPolicy);
    }

    @Test
    void uploadRejectsNullFileAndUnauthorizedUploader() {
        DomainException nullFile = assertThrows(
                DomainException.class,
                () -> mediaService.uploadDirect(ownerId, folderId, (org.springframework.web.multipart.MultipartFile) null));
        assertEquals("INVALID_ARGUMENT", nullFile.getErrorCode().name());

        folderCapabilityPolicy.deny = true;
        DomainException unauthorized = assertThrows(
                DomainException.class,
                () -> mediaService.uploadDirect(UUID.randomUUID(), folderId, imageFile()));
        assertEquals("FOLDER_WRITE_NOT_ALLOWED", unauthorized.getErrorCode().name());
    }

    @Test
    void uploadAuthorizesFolderWriteThroughCapabilityPolicy() {
        MediaAsset uploaded = mediaService.uploadDirect(ownerId, folderId, imageFile());

        assertEquals(ownerId, folderCapabilityPolicy.lastActorId);
        assertEquals(folderId, folderCapabilityPolicy.lastFolderId);
        assertEquals(1, folderCapabilityPolicy.invocations);
        assertEquals(folderId, uploaded.intendedFolderId());
    }

    @Test
    void uploadRequiresExactDeclaredChecksumGrammar() {
        for (String checksum : List.of("", " " + "0".repeat(64), "0".repeat(63), "g".repeat(64))) {
            DomainException exception = assertThrows(
                    DomainException.class,
                    () -> mediaService.uploadDirect(ownerId, folderId, List.of(imageFile()), checksum));
            assertEquals("INVALID_ARGUMENT", exception.getErrorCode().name());
        }
    }

    @Test
    void pendingMediaCannotCommitAtItsExpirationBoundary() {
        MediaAsset pending = pendingAsset(NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> pending.commit(folderId, UUID.randomUUID(), NOW.plusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> pending.commit(folderId, UUID.randomUUID(), NOW.plusSeconds(2)));
        assertEquals(MediaAsset.Status.COMMITTED, pending.commit(folderId, UUID.randomUUID(), NOW).status());
    }

    @Test
    void uploadAcceptsExactChecksumAndPreservesMultipartReadFailureCause() {
        String checksum = sha256(TINY_PNG);
        MediaAsset uploaded = mediaService.uploadDirect(ownerId, folderId, List.of(imageFile()), checksum.toUpperCase());
        assertEquals(checksum, uploaded.checksumSha256());

        IOException readFailure = new IOException("read failed");
        MockMultipartFile unreadable = new MockMultipartFile("file", "photo.png", "image/png", TINY_PNG) {
            @Override
            public byte[] getBytes() throws IOException {
                throw readFailure;
            }
        };
        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> mediaService.uploadDirect(ownerId, folderId, unreadable));
        assertSame(readFailure, exception.getCause());
    }

    @Test
    void uploadRejectsHostileFilenameAndOversizedPixelHeader() throws Exception {
        DomainException hostile = assertThrows(DomainException.class, () -> mediaService.uploadDirect(
                ownerId, folderId, new MockMultipartFile("file", "bad\u0000name.png", "image/png", TINY_PNG)));
        assertEquals("INVALID_ARGUMENT", hostile.getErrorCode().name());

        byte[] oversizedPixels = validOversizedGrayscalePng(10_000, 4_001);
        var decoded = ImageIO.read(new ByteArrayInputStream(oversizedPixels));
        assertNotNull(decoded);
        assertEquals(10_000, decoded.getWidth());
        assertEquals(4_001, decoded.getHeight());
        DomainException rejected = assertThrows(
                DomainException.class,
                () -> mediaService.uploadDirect(
                        ownerId, folderId, new MockMultipartFile("file", "pixels.png", "image/png", oversizedPixels)));
        assertEquals("INVALID_ARGUMENT", rejected.getErrorCode().name());
        assertEquals("Uploaded image dimensions exceed the allowed limit.", rejected.getMessage());
    }

    @Test
    void fileStoreRejectsWrongTypeAssetPathAndConditionallyCommitsOnce() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path root = tempDir.resolve("atomic");
        FileMediaAssetStore store = new FileMediaAssetStore(mapper, root);
        UUID wrongTypeId = UUID.randomUUID();
        Files.createDirectories(root.resolve("assets").resolve(wrongTypeId + ".json"));
        assertThrows(IllegalStateException.class, () -> store.findById(wrongTypeId));
        assertThrows(IllegalStateException.class, () -> store.cleanupExpiredPending(NOW.plusSeconds(2)));
        Files.delete(root.resolve("assets").resolve(wrongTypeId + ".json"));

        MediaAsset pending = pendingAsset(NOW.plusSeconds(1));
        store.saveWithBlob(pending, TINY_PNG);
        MediaAsset committed = store.commitPending(pending.mediaId(), ownerId, folderId, UUID.randomUUID(), NOW);
        assertEquals(MediaAsset.Status.COMMITTED, committed.status());
        assertThrows(
                MediaAssetStore.PendingCommitException.class,
                () -> store.commitPending(pending.mediaId(), ownerId, folderId, UUID.randomUUID(), NOW));
        assertEquals(0, store.cleanupExpiredPending(NOW.plusSeconds(2)));
        assertEquals(MediaAsset.Status.COMMITTED, store.findById(pending.mediaId()).orElseThrow().status());
    }

    @Test
    void fileStoreRejectsBlobMetadataMismatch() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileMediaAssetStore store = new FileMediaAssetStore(mapper, tempDir.resolve("mismatch"));
        MediaAsset pending = pendingAsset(NOW.plusSeconds(1));

        assertThrows(IllegalStateException.class, () -> store.saveWithBlob(pending, new byte[] {1}));
        byte[] corrupted = TINY_PNG.clone();
        corrupted[corrupted.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> store.saveWithBlob(pending, corrupted));
        assertTrue(store.findById(pending.mediaId()).isEmpty());
    }

    @Test
    void cleanupScansPastFormerBatchBoundaryAndIsScheduled() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileMediaAssetStore store = new FileMediaAssetStore(mapper, tempDir.resolve("cleanup-batch"));
        for (int index = 0; index < 1_000; index++) {
            MediaAsset pending = pendingAsset(new UUID(0, index), NOW.plusSeconds(1));
            store.saveWithBlob(pending, TINY_PNG);
            store.commitPending(pending.mediaId(), ownerId, folderId, UUID.randomUUID(), NOW);
        }
        MediaAsset expired = pendingAsset(new UUID(0, 1_000), NOW.plusSeconds(1));
        store.saveWithBlob(expired, TINY_PNG);

        assertEquals(1, store.cleanupExpiredPending(NOW.plusSeconds(2)));
        assertTrue(store.findById(expired.mediaId()).isEmpty());
        assertNotNull(MediaService.class.getMethod("cleanupExpiredPending")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class));
    }

    @Test
    void serviceCommitRejectsAtAndAfterExpirationWithoutStateTransition() {
        for (Instant commitTime : List.of(NOW.plusSeconds(1), NOW.plusSeconds(2))) {
            RecordingStore store = new RecordingStore(pendingAsset(NOW.plusSeconds(1)));
            MediaService service = service(store, commitTime);
            DomainException exception = assertThrows(
                    DomainException.class,
                    () -> service.commitDiaryMedia(store.asset, folderId, UUID.randomUUID()));
            assertEquals("INVALID_ARGUMENT", exception.getErrorCode().name());
            assertEquals(MediaAsset.Status.PENDING, store.asset.status());
            assertEquals(0, store.successfulCommits);
        }
    }

    @Test
    void uploadStoreFailureIsPropagatedWithoutAFalseSuccessfulAsset() {
        RuntimeException persistenceFailure = new IllegalStateException("metadata move failed");
        MediaService service = service(new FailingSaveStore(persistenceFailure), NOW);
        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> service.uploadDirect(ownerId, folderId, imageFile()));
        assertSame(persistenceFailure, thrown);
    }

    @Test
    void failedAtomicPublishRemovesStagedFileAndLeavesNoVisibleAsset() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path root = tempDir.resolve("failed-publish");
        AtomicReference<Path> stagedPath = new AtomicReference<>();
        IOException moveFailure = new IOException("injected atomic move failure");
        FileMediaAssetStore store = new FileMediaAssetStore(mapper, root, (source, target) -> {
            stagedPath.set(source);
            assertTrue(Files.isRegularFile(source));
            throw moveFailure;
        });
        MediaAsset pending = pendingAsset(NOW.plusSeconds(60));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> store.saveWithBlob(pending, TINY_PNG));

        assertSame(moveFailure, failure.getCause());
        assertTrue(store.findById(pending.mediaId()).isEmpty());
        assertFalse(Files.exists(stagedPath.get()));
        try (var files = Files.list(root.resolve("assets"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".json")
                    || path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void crossInstanceCommitAndCleanupRaceLeavesOnlyACommittedAssetOrConfirmedAbsence() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path root = tempDir.resolve("race");
        FileMediaAssetStore committingStore = new FileMediaAssetStore(mapper, root);
        FileMediaAssetStore cleanupStore = new FileMediaAssetStore(mapper, root);
        MediaAsset pending = pendingAsset(NOW.plusSeconds(1));
        committingStore.saveWithBlob(pending, TINY_PNG);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Boolean> committed = CompletableFuture.supplyAsync(() -> {
            await(start);
            try {
                committingStore.commitPending(pending.mediaId(), ownerId, folderId, UUID.randomUUID(), NOW);
                return true;
            } catch (MediaAssetStore.AssetNotFoundException exception) {
                return false;
            }
        });
        CompletableFuture<Integer> removed = CompletableFuture.supplyAsync(() -> {
            await(start);
            return cleanupStore.cleanupExpiredPending(NOW.plusSeconds(2));
        });
        start.countDown();

        boolean commitWon = committed.get(5, TimeUnit.SECONDS);
        int cleanupCount = removed.get(5, TimeUnit.SECONDS);
        Optional<MediaAsset> finalAsset = committingStore.findById(pending.mediaId());
        if (commitWon) {
            assertEquals(0, cleanupCount);
            assertEquals(MediaAsset.Status.COMMITTED, finalAsset.orElseThrow().status());
        } else {
            assertEquals(1, cleanupCount);
            assertTrue(finalAsset.isEmpty());
        }
    }
    private MediaService service(MediaAssetStore store, Instant now) {
        return new MediaService(
                store,
                new ExifMetadataExtractor(new ObjectMapper().findAndRegisterModules()),
                collaborationStore,
                folderCapabilityPolicy,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte byteValue : digest) {
                value.append(String.format("%02x", byteValue & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] validOversizedGrayscalePng(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(13);
        try (DataOutputStream header = new DataOutputStream(headerBytes)) {
            header.writeInt(width);
            header.writeInt(height);
            header.writeByte(8);
            header.writeByte(0);
            header.writeByte(0);
            header.writeByte(0);
            header.writeByte(0);
        }
        writePngChunk(output, "IHDR", headerBytes.toByteArray());

        byte[] scanlines = new byte[Math.multiplyExact(width + 1, height)];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(scanlines);
        }
        writePngChunk(output, "IDAT", compressed.toByteArray());
        writePngChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writePngChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        DataOutputStream chunk = new DataOutputStream(output);
        chunk.writeInt(data.length);
        chunk.write(typeBytes);
        chunk.write(data);
        chunk.writeInt((int) crc.getValue());
        chunk.flush();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for media lifecycle race.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingFolderCapabilityPolicy implements FolderCapabilityPolicy {
        private UUID lastActorId;
        private UUID lastFolderId;
        private int invocations;
        private boolean deny;

        @Override
        public void assertCanWriteToFolder(UUID actorId, UUID folderId) {
            lastActorId = actorId;
            lastFolderId = folderId;
            invocations++;
            if (deny) {
                throw FolderCapabilityPolicy.folderWriteNotAllowed(actorId, folderId);
            }
        }
    }
    private static class RecordingStore implements MediaAssetStore {
        private MediaAsset asset;
        private int successfulCommits;
        private UUID generation = UUID.randomUUID();

        private RecordingStore(MediaAsset asset) {
            this.asset = asset;
        }

        @Override
        public MediaAsset saveWithBlob(MediaAsset mediaAsset, byte[] blobBytes) {
            asset = mediaAsset;
            generation = UUID.randomUUID();
            return mediaAsset;
        }

        @Override
        public MediaAsset commitPending(
                UUID mediaId, UUID uploaderUserId, UUID folderId, UUID diaryEntryId, Instant committedAt) {
            try {
                MediaAsset committed = asset.commit(folderId, diaryEntryId, committedAt);
                asset = committed;
                generation = UUID.randomUUID();
                successfulCommits++;
                return committed;
            } catch (IllegalStateException exception) {
                throw new MediaAssetStore.PendingCommitException(exception.getMessage());
            }
        }

        @Override
        public int cleanupExpiredPending(Instant now) {
            if (asset != null && asset.isExpired(now)) {
                asset = null;
                return 1;
            }
            return 0;
        }

        @Override
        public Optional<MediaAssetStore.Metadata> findMetadata(UUID mediaId) {
            return findById(mediaId).map(found -> new MediaAssetStore.Metadata(found, generation));
        }

        @Override
        public MediaAssetStore.Snapshot readSnapshot(MediaAssetStore.Metadata metadata) {
            if (asset == null || !asset.equals(metadata.mediaAsset()) || !generation.equals(metadata.generation())) {
                throw new MediaAssetStore.SnapshotChangedException(metadata.mediaAsset().mediaId());
            }
            return new MediaAssetStore.Snapshot(asset, generation, TINY_PNG);
        }

        @Override
        public Optional<MediaAsset> findById(UUID mediaId) {
            return asset != null && asset.mediaId().equals(mediaId) ? Optional.of(asset) : Optional.empty();
        }
    }

    private static final class FailingSaveStore extends RecordingStore {
        private final RuntimeException failure;

        private FailingSaveStore(RuntimeException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public MediaAsset saveWithBlob(MediaAsset mediaAsset, byte[] blobBytes) {
            throw failure;
        }
    }
    private MockMultipartFile imageFile() {
        return new MockMultipartFile("file", "photo.png", "image/png", TINY_PNG);
    }

    private MediaAsset pendingAsset(Instant expiresAt) {
        return pendingAsset(UUID.randomUUID(), expiresAt);
    }

    private MediaAsset pendingAsset(UUID mediaId, Instant expiresAt) {
        return new MediaAsset(
                mediaId,
                ownerId,
                "asset.png",
                folderId,
                "photo.png",
                "image/png",
                TINY_PNG.length,
                1,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                sha256(TINY_PNG),
                MediaAsset.Status.PENDING,
                null,
                null,
                NOW,
                expiresAt,
                null);
    }
}
