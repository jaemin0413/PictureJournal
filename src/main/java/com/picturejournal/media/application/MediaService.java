package com.picturejournal.media.application;

import com.picturejournal.collaboration.application.CollaborationStore;
import com.picturejournal.folder.domain.FolderRole;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
    private static final Duration PENDING_TTL = Duration.ofHours(24);
    private static final long MAX_UPLOAD_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_IMAGE_WIDTH = 10_000;
    private static final int MAX_IMAGE_HEIGHT = 10_000;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private final MediaAssetStore mediaAssetStore;
    private final CollaborationStore collaborationStore;
    private final ExifMetadataExtractor exifMetadataExtractor;
    private final Clock clock;

    @Autowired
    public MediaService(
            MediaAssetStore mediaAssetStore,
            ExifMetadataExtractor exifMetadataExtractor,
            CollaborationStore collaborationStore) {
        this(mediaAssetStore, exifMetadataExtractor, collaborationStore, Clock.systemUTC());
    }

    public MediaService(
            MediaAssetStore mediaAssetStore,
            ExifMetadataExtractor exifMetadataExtractor,
            CollaborationStore collaborationStore,
            Clock clock) {
        this.mediaAssetStore = Objects.requireNonNull(mediaAssetStore, "mediaAssetStore must not be null");
        this.exifMetadataExtractor = Objects.requireNonNull(exifMetadataExtractor, "exifMetadataExtractor must not be null");
        this.collaborationStore = Objects.requireNonNull(collaborationStore, "collaborationStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public MediaAsset uploadDirect(UUID actorId, UUID intendedFolderId, MultipartFile file) {
        if (file == null) {
            throw invalidArgument("file is required.");
        }
        return uploadDirect(actorId, intendedFolderId, List.of(file), null);
    }

    public MediaAsset uploadDirect(
            UUID actorId,
            UUID intendedFolderId,
            List<MultipartFile> files,
            String declaredChecksumSha256) {
        if (files == null || files.size() != 1 || files.getFirst() == null) {
            throw invalidArgument("Exactly one file part is required.");
        }
        if (intendedFolderId == null) {
            throw invalidArgument("intendedFolderId is required.");
        }
        var folder = collaborationStore.findFolderById(intendedFolderId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Folder " + intendedFolderId + " was not found."));
        if (folder.type() != FolderType.PHOTO_DIARY) {
            throw invalidArgument("Media uploads are only allowed for PHOTO_DIARY folders.");
        }
        var membership = collaborationStore.findMembership(intendedFolderId, actorId)
                .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "Only folder members can upload media."));
        if (membership.role() == FolderRole.VIEWER) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Viewer members cannot upload media.");
        }
        MultipartFile file = files.getFirst();
        if (file.isEmpty()) {
            throw invalidArgument("file is required.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw invalidArgument("Uploaded image exceeds the 20 MiB limit.");
        }
        byte[] bytes = readBytes(file);
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw invalidArgument("Uploaded image exceeds the 20 MiB limit.");
        }
        String mimeType = detectMimeType(bytes);
        String suppliedMimeType = normalizeMimeType(file.getContentType());
        if (!suppliedMimeType.isEmpty() && !mimeType.equals(suppliedMimeType)) {
            throw invalidArgument("Uploaded file content does not match its declared MIME type.");
        }
        String checksum = checksumSha256(bytes);
        validateDeclaredChecksum(declaredChecksumSha256, checksum);
        Dimensions dimensions = readDimensions(bytes);
        ExifMetadataExtractor.ExtractedExif exif = exifMetadataExtractor.extract(bytes);
        UUID mediaId = UUID.randomUUID();
        String storageKey = mediaId + extensionFor(mimeType);
        Instant now = Instant.now(clock);
        MediaAsset mediaAsset = new MediaAsset(
                mediaId,
                actorId,
                storageKey,
                intendedFolderId,
                normalizeOriginalFilename(file.getOriginalFilename()),
                mimeType,
                bytes.length,
                dimensions.width(),
                dimensions.height(),
                exif.exifJson(),
                exif.takenAt(),
                exif.cameraMake(),
                exif.cameraModel(),
                exif.gpsLatitude(),
                exif.gpsLongitude(),
                checksum,
                MediaAsset.Status.PENDING,
                null,
                null,
                now,
                now.plus(PENDING_TTL),
                null);
        return mediaAssetStore.saveWithBlob(mediaAsset, bytes);
    }

    public MediaAsset requireMedia(UUID mediaId) {
        MediaAsset mediaAsset = mediaAssetStore.findById(mediaId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Media asset " + mediaId + " was not found."));
        requireNotExpired(mediaAsset);
        return mediaAsset;
    }

    public MediaAsset requirePendingUpload(UUID actorId, UUID folderId, UUID mediaId) {
        MediaAsset mediaAsset = requirePendingUpload(actorId, mediaId);
        if (!folderId.equals(mediaAsset.intendedFolderId())) {
            throw invalidArgument("Media asset " + mediaId + " was uploaded for a different folder.");
        }
        return mediaAsset;
    }

    public MediaAsset requirePendingUpload(UUID actorId, UUID mediaId) {
        MediaAsset mediaAsset = requireMedia(mediaId);
        if (!mediaAsset.uploaderUserId().equals(actorId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the uploader can attach this media asset.");
        }
        if (!mediaAsset.isPending()) {
            throw invalidArgument("Media asset " + mediaId + " has already been committed.");
        }
        return mediaAsset;
    }

    public MediaAsset commitDiaryMedia(MediaAsset mediaAsset, UUID folderId, UUID diaryEntryId) {
        try {
            return mediaAssetStore.commitPending(
                    mediaAsset.mediaId(),
                    mediaAsset.uploaderUserId(),
                    folderId,
                    diaryEntryId,
                    Instant.now(clock));
        } catch (MediaAssetStore.AssetNotFoundException exception) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
        } catch (MediaAssetStore.PendingCommitException exception) {
            throw invalidArgument(exception.getMessage());
        }
    }

    public BinaryMedia readAuthorizedBinary(UUID actorId, UUID mediaId) {
        MediaAssetStore.Metadata metadata = mediaAssetStore.findMetadata(mediaId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Media asset " + mediaId + " was not found."));
        MediaAsset mediaAsset = metadata.mediaAsset();
        requireNotExpired(mediaAsset);
        if (mediaAsset.isPending()) {
            if (!mediaAsset.uploaderUserId().equals(actorId)) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Only the uploader can read this pending media asset.");
            }
        } else {
            collaborationStore.findMembership(mediaAsset.committedFolderId(), actorId)
                    .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "Only folder members can read this media asset."));
        }
        MediaAssetStore.Snapshot snapshot = readAndValidatePersistedSnapshot(metadata);
        return new BinaryMedia(snapshot.mediaAsset(), snapshot.bytes());
    }

    @Scheduled(
            fixedDelayString = "${app.media.cleanup-interval-ms:3600000}",
            initialDelayString = "${app.media.cleanup-initial-delay-ms:60000}")
    public int cleanupExpiredPending() {
        return mediaAssetStore.cleanupExpiredPending(Instant.now(clock));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read uploaded file.", exception);
        }
    }

    private Dimensions readDimensions(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw invalidArgument("Uploaded file is not a readable image.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidArgument("Uploaded file is not a readable image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_WIDTH || height > MAX_IMAGE_HEIGHT
                        || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw invalidArgument("Uploaded image dimensions exceed the allowed limit.");
                }
                if (reader.read(0) == null) {
                    throw invalidArgument("Uploaded file is not a readable image.");
                }
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw invalidArgument("Uploaded file is not a readable image.");
        }
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        throw invalidArgument("Only image/jpeg and image/png files are supported.");
    }

    private void validateDeclaredChecksum(String declaredChecksumSha256, String checksum) {
        if (declaredChecksumSha256 == null) {
            return;
        }
        if (!declaredChecksumSha256.matches("[a-fA-F0-9]{64}")) {
            throw invalidArgument("checksumSha256 must be exactly 64 hexadecimal characters.");
        }
        if (!checksum.equalsIgnoreCase(declaredChecksumSha256)) {
            throw invalidArgument("Uploaded file checksum does not match the declared checksum.");
        }
    }

    private MediaAssetStore.Snapshot readAndValidatePersistedSnapshot(MediaAssetStore.Metadata authorizedMetadata) {
        MediaAsset mediaAsset = authorizedMetadata.mediaAsset();
        final MediaAssetStore.Snapshot snapshot;
        try {
            snapshot = mediaAssetStore.readSnapshot(authorizedMetadata);
        } catch (MediaAssetStore.AssetNotFoundException exception) {
            throw storageIntegrityFailure(mediaAsset, exception);
        } catch (MediaAssetStore.SnapshotChangedException exception) {
            throw storageIntegrityFailure(mediaAsset, exception);
        }
        byte[] bytes = snapshot.bytes();
        if (bytes.length == 0) {
            throw storageIntegrityFailure(mediaAsset, null);
        }
        final String mimeType;
        try {
            mimeType = detectMimeType(bytes);
        } catch (DomainException exception) {
            throw storageIntegrityFailure(mediaAsset, exception);
        }
        if (!mimeType.equals(snapshot.mediaAsset().mimeType())
                || bytes.length != snapshot.mediaAsset().sizeBytes()
                || !checksumSha256(bytes).equals(snapshot.mediaAsset().checksumSha256())) {
            throw storageIntegrityFailure(snapshot.mediaAsset(), null);
        }
        return snapshot;
    }

    private IllegalStateException storageIntegrityFailure(MediaAsset mediaAsset, Throwable cause) {
        String message = "Persisted media asset " + mediaAsset.mediaId() + " failed its storage integrity check.";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private void requireNotExpired(MediaAsset mediaAsset) {
        if (mediaAsset.isExpired(Instant.now(clock))) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Media asset " + mediaAsset.mediaId() + " has expired.");
        }
    }

    private String checksumSha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return mimeType.toLowerCase(Locale.ROOT).trim();
    }

    private String extensionFor(String mimeType) {
        return "image/png".equals(mimeType) ? ".png" : ".jpg";
    }

    private String normalizeOriginalFilename(String value) {
        if (value == null) {
            return null;
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw invalidArgument("original filename contains control characters.");
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private DomainException invalidArgument(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }

    public record BinaryMedia(MediaAsset mediaAsset, byte[] bytes) {
    }


    private record Dimensions(Integer width, Integer height) {
    }
}
