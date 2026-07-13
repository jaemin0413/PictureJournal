package com.picturejournal.media.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.media.domain.MediaAsset;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileMediaAssetStore implements MediaAssetStore {
    private static final int FORMAT_MAGIC = 0x504A4D41; // PJMA
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 20 * 1024 * 1024;
    private static final ConcurrentHashMap<Path, Object> JVM_ASSET_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;
    private final Path assetsDirectory;
    private final AtomicMover atomicMover;

    @Autowired
    public FileMediaAssetStore(ObjectMapper objectMapper, @Value("${app.media.root:data/media}") String rootDirectory) {
        this(objectMapper, Paths.get(rootDirectory));
    }

    public FileMediaAssetStore(ObjectMapper objectMapper, Path rootDirectory) {
        this(objectMapper, rootDirectory, (source, target) ->
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    FileMediaAssetStore(ObjectMapper objectMapper, Path rootDirectory, AtomicMover atomicMover) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null").toAbsolutePath().normalize();
        this.assetsDirectory = this.rootDirectory.resolve("assets").normalize();
        this.atomicMover = Objects.requireNonNull(atomicMover, "atomicMover must not be null");
        if (!this.assetsDirectory.startsWith(this.rootDirectory)) {
            throw new IllegalArgumentException("Media assets directory must be inside the storage root.");
        }
    }

    @Override
    public MediaAsset saveWithBlob(MediaAsset mediaAsset, byte[] blobBytes) {
        Objects.requireNonNull(mediaAsset, "mediaAsset must not be null");
        Objects.requireNonNull(blobBytes, "blobBytes must not be null");
        return withAssetLock(mediaAsset.mediaId(), () -> {
            writeUnitAtomically(assetPath(mediaAsset.mediaId()), mediaAsset, UUID.randomUUID(), blobBytes, "media asset " + mediaAsset.mediaId());
            return mediaAsset;
        });
    }

    @Override
    public MediaAsset commitPending(UUID mediaId, UUID uploaderUserId, UUID folderId, UUID diaryEntryId, Instant committedAt) {
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(uploaderUserId, "uploaderUserId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(diaryEntryId, "diaryEntryId must not be null");
        Objects.requireNonNull(committedAt, "committedAt must not be null");
        return withAssetLock(mediaId, () -> {
            StoredHeader stored = readHeaderOrThrow(mediaId);
            MediaAsset pending = stored.mediaAsset();
            if (!pending.isPending()) {
                throw new MediaAssetStore.PendingCommitException("media asset is already committed");
            }
            if (!pending.uploaderUserId().equals(uploaderUserId)) {
                throw new MediaAssetStore.PendingCommitException("media asset uploader does not match the pending upload");
            }
            MediaAsset committed;
            try {
                committed = pending.commit(folderId, diaryEntryId, committedAt);
            } catch (IllegalStateException exception) {
                throw new MediaAssetStore.PendingCommitException(exception.getMessage());
            }
            byte[] bytes = readPayload(assetPath(mediaId), stored);
            writeUnitAtomically(assetPath(mediaId), committed, UUID.randomUUID(), bytes, "media asset " + mediaId);
            return committed;
        });
    }

    @Override
    public Optional<MediaAsset> findById(UUID mediaId) {
        return findMetadata(mediaId).map(MediaAssetStore.Metadata::mediaAsset);
    }

    @Override
    public Optional<Metadata> findMetadata(UUID mediaId) {
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        if (!Files.exists(assetsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        ensureManagedAssetsDirectory();
        Path path = assetPath(mediaId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        StoredHeader header = readHeader(path, mediaId);
        return Optional.of(new Metadata(header.mediaAsset(), header.generation()));
    }


    @Override
    public Snapshot readSnapshot(Metadata authorizedMetadata) {
        Objects.requireNonNull(authorizedMetadata, "authorizedMetadata must not be null");
        UUID mediaId = authorizedMetadata.mediaAsset().mediaId();
        return withAssetLock(mediaId, () -> {
            StoredHeader current = readHeaderOrThrow(mediaId);
            if (!authorizedMetadata.generation().equals(current.generation())) {
                throw new MediaAssetStore.SnapshotChangedException(mediaId);
            }
            if (!authorizedMetadata.mediaAsset().equals(current.mediaAsset())) {
                throw new MediaAssetStore.SnapshotChangedException(mediaId);
            }
            return new Snapshot(current.mediaAsset(), current.generation(), readPayload(assetPath(mediaId), current));
        });
    }

    @Override
    public int cleanupExpiredPending(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        int removed = 0;
        for (UUID mediaId : listAssetIds()) {
            if (withAssetLock(mediaId, () -> deleteExpiredPending(mediaId, now))) {
                removed++;
            }
        }
        return removed;
    }

    private boolean deleteExpiredPending(UUID mediaId, Instant now) {
        Path path = assetPath(mediaId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        StoredHeader stored = readHeader(path, mediaId);
        if (!stored.mediaAsset().isExpired(now)) {
            return false;
        }
        try {
            Files.delete(path);
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to remove expired media asset " + mediaId, exception);
        }
    }

    private List<UUID> listAssetIds() {
        if (!Files.exists(assetsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        ensureManagedAssetsDirectory();
        try (Stream<Path> paths = Files.list(assetsDirectory)) {
            return paths.map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".json"))
                    .sorted()
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .map(this::parseMediaId)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list media assets", exception);
        }
    }

    private UUID parseMediaId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid media asset record name: " + value, exception);
        }
    }

    private StoredHeader readHeaderOrThrow(UUID mediaId) {
        Path path = assetPath(mediaId);
        if (Files.notExists(path)) {
            throw new MediaAssetStore.AssetNotFoundException(mediaId);
        }
        return readHeader(path, mediaId);
    }

    private StoredHeader readHeader(Path path, UUID expectedMediaId) {
        requireRegularFile(path, "media asset " + expectedMediaId);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            StoredHeader header = readFrameHeader(input, expectedMediaId);
            return header;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read media asset " + expectedMediaId, exception);
        }
    }

    private byte[] readPayload(Path path, StoredHeader expected) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            StoredHeader actual = readFrameHeader(input, expected.mediaAsset().mediaId());
            if (!expected.generation().equals(actual.generation()) || !expected.mediaAsset().equals(actual.mediaAsset())) {
                throw new MediaAssetStore.SnapshotChangedException(expected.mediaAsset().mediaId());
            }
            if (actual.payloadLength() > Integer.MAX_VALUE) {
                throw new IllegalStateException("Media payload is too large: " + expected.mediaAsset().mediaId());
            }
            byte[] payload = input.readNBytes((int) actual.payloadLength());
            if (payload.length != actual.payloadLength() || input.read() != -1) {
                throw new IllegalStateException("Media payload is incomplete: " + expected.mediaAsset().mediaId());
            }
            return payload;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read media payload " + expected.mediaAsset().mediaId(), exception);
        }
    }

    private StoredHeader readFrameHeader(DataInputStream input, UUID expectedMediaId) throws IOException {
        if (input.readInt() != FORMAT_MAGIC || input.readInt() != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported media asset format: " + expectedMediaId);
        }
        UUID generation = new UUID(input.readLong(), input.readLong());
        int metadataLength = input.readInt();
        long payloadLength = input.readLong();
        if (metadataLength <= 0 || metadataLength > MAX_METADATA_BYTES
                || payloadLength <= 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("Media asset frame is invalid: " + expectedMediaId);
        }
        byte[] metadataBytes = input.readNBytes(metadataLength);
        if (metadataBytes.length != metadataLength) {
            throw new IllegalStateException("Media asset metadata is incomplete: " + expectedMediaId);
        }
        MediaAsset mediaAsset;
        try {
            mediaAsset = objectMapper.readValue(metadataBytes, MediaAsset.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Media asset metadata is invalid: " + expectedMediaId, exception);
        }
        if (!expectedMediaId.equals(mediaAsset.mediaId())) {
            throw new IllegalStateException("Media asset record ID does not match its path: " + expectedMediaId);
        }
        return new StoredHeader(mediaAsset, generation, payloadLength);
    }

    private Path assetPath(UUID mediaId) {
        Path path = assetsDirectory.resolve(mediaId + ".json").normalize();
        if (!path.startsWith(assetsDirectory)) {
            throw new IllegalArgumentException("Media asset path escapes the storage root.");
        }
        return path;
    }

    private Path lockPath(UUID mediaId) {
        Path path = assetsDirectory.resolve(mediaId + ".lock").normalize();
        if (!path.startsWith(assetsDirectory)) {
            throw new IllegalArgumentException("Media asset lock path escapes the storage root.");
        }
        return path;
    }

    private <T> T withAssetLock(UUID mediaId, LockedOperation<T> operation) {
        Path lockPath = lockPath(mediaId);
        Object jvmLock = JVM_ASSET_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        synchronized (jvmLock) {
            try {
                Files.createDirectories(assetsDirectory);
                ensureManagedAssetsDirectory();
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(lockPath)) {
                    throw new IllegalStateException("Media asset lock path must not be a symbolic link: " + lockPath);
                }
                try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        FileLock ignored = channel.lock()) {
                    return operation.execute();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to lock media asset " + mediaId, exception);
            }
        }
    }

    private void writeUnitAtomically(Path path, MediaAsset mediaAsset, UUID generation, byte[] payload, String label) {
        Path temp = null;
        RuntimeException failure = null;
        try {
            temp = writeUnitTemp(path, mediaAsset, generation, payload);
            moveAtomically(temp, path, label);
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            deleteTemp(temp, failure);
        }
    }

    private Path writeUnitTemp(Path path, MediaAsset mediaAsset, UUID generation, byte[] payload) {
        Path temp = null;
        try {
            validatePayload(mediaAsset, payload);
            byte[] metadata = objectMapper.writeValueAsBytes(mediaAsset);
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("Media payload exceeds the frame limit: " + mediaAsset.mediaId());
            }
            if (metadata.length == 0 || metadata.length > MAX_METADATA_BYTES) {
                throw new IllegalStateException("Media asset metadata exceeds the frame limit: " + mediaAsset.mediaId());
            }
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temp))) {
                output.writeInt(FORMAT_MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeLong(generation.getMostSignificantBits());
                output.writeLong(generation.getLeastSignificantBits());
                output.writeInt(metadata.length);
                output.writeLong(payload.length);
                output.write(metadata);
                output.write(payload);
            }
            return temp;
        } catch (IOException exception) {
            IllegalStateException failure = new IllegalStateException("Failed to persist " + path.getFileName(), exception);
            deleteTemp(temp, failure);
            throw failure;
        } catch (RuntimeException exception) {
            deleteTemp(temp, exception);
            throw exception;
        }
    }
    private void validatePayload(MediaAsset mediaAsset, byte[] payload) {
        if (payload.length == 0 || payload.length != mediaAsset.sizeBytes()) {
            throw new IllegalStateException("Media payload length does not match metadata: " + mediaAsset.mediaId());
        }
        try {
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
            if (!checksum.equalsIgnoreCase(mediaAsset.checksumSha256())) {
                throw new IllegalStateException("Media payload checksum does not match metadata: " + mediaAsset.mediaId());
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void moveAtomically(Path source, Path target, String label) {
        try {
            atomicMover.move(source, target);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IllegalStateException("Atomic move is unavailable for " + label, exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist " + label, exception);
        }
    }

    private void requireRegularFile(Path path, String label) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Expected " + label + " to be a readable managed regular file: " + path);
        }
    }

    private void ensureManagedAssetsDirectory() {
        try {
            if (Files.isSymbolicLink(assetsDirectory)) {
                throw new IllegalStateException("Media assets directory must not be a symbolic link: " + assetsDirectory);
            }
            Path rootReal = rootDirectory.toRealPath();
            Path assetsReal = assetsDirectory.toRealPath();
            if (!assetsReal.startsWith(rootReal)) {
                throw new IllegalStateException("Media assets directory escapes the storage root.");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to validate the media assets directory.", exception);
        }
    }

    private void deleteTemp(Path temp, RuntimeException primaryFailure) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException cleanupException) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupException);
                return;
            }
            throw new IllegalStateException("Failed to clean up temporary media asset " + temp, cleanupException);
        }
    }

    private record StoredHeader(MediaAsset mediaAsset, UUID generation, long payloadLength) {
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T execute();
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }
}
