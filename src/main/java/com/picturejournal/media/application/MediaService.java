package com.picturejournal.media.application;

import com.picturejournal.collaboration.application.CollaborationStore;
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.folder.domain.FolderRole;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
    private static final Duration PENDING_TTL = Duration.ofHours(24);

    private final MediaAssetStore mediaAssetStore;
    private final FileMediaAssetStore fileMediaAssetStore;
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

    public MediaService(MediaAssetStore mediaAssetStore, ExifMetadataExtractor exifMetadataExtractor) {
        this(mediaAssetStore, exifMetadataExtractor, null, Clock.systemUTC());
    }

    MediaService(
            MediaAssetStore mediaAssetStore,
            ExifMetadataExtractor exifMetadataExtractor,
            CollaborationStore collaborationStore,
            Clock clock) {
        this.mediaAssetStore = mediaAssetStore;
        this.fileMediaAssetStore = mediaAssetStore instanceof FileMediaAssetStore fileStore ? fileStore : null;
        this.collaborationStore = collaborationStore;
        this.exifMetadataExtractor = exifMetadataExtractor;
        this.clock = clock;
    }

    public MediaAsset uploadDirect(UUID actorId, UUID intendedFolderId, MultipartFile file) {
        if (intendedFolderId == null) {
            throw invalidArgument("intendedFolderId is required.");
        }
        if (collaborationStore != null) {
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
        }
        if (file == null || file.isEmpty()) {
            throw invalidArgument("file is required.");
        }
        byte[] bytes = readBytes(file);
        String mimeType = detectMimeType(bytes);
        String suppliedMimeType = normalizeMimeType(file.getContentType());
        if (!suppliedMimeType.isEmpty() && !mimeType.equals(suppliedMimeType)) {
            throw invalidArgument("Uploaded file content does not match its declared MIME type.");
        }
        Dimensions dimensions = readDimensions(bytes);
        ExifMetadataExtractor.ExtractedExif exif = exifMetadataExtractor.extract(bytes);
        UUID mediaId = UUID.randomUUID();
        String storageKey = mediaId + extensionFor(mimeType);
        persistBlob(storageKey, bytes);
        Instant now = Instant.now(clock);
        MediaAsset mediaAsset = new MediaAsset(
                mediaId,
                actorId,
                storageKey,
                intendedFolderId,
                normalizeOptional(file.getOriginalFilename()),
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
                checksumSha256(bytes),
                MediaAsset.Status.PENDING,
                null,
                null,
                now,
                now.plus(PENDING_TTL),
                null);
        return mediaAssetStore.save(mediaAsset);
    }

    public MediaAsset requireMedia(UUID mediaId) {
        MediaAsset mediaAsset = mediaAssetStore.findById(mediaId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Media asset " + mediaId + " was not found."));
        requireNotExpired(mediaAsset);
        requirePersistedBlob(mediaAsset);
        return mediaAsset;
    }

    public MediaAsset requirePendingUpload(UUID actorId, UUID folderId, UUID mediaId) {
        MediaAsset mediaAsset = requireMedia(mediaId);
        if (!mediaAsset.uploaderUserId().equals(actorId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the uploader can attach this media asset.");
        }
        if (!folderId.equals(mediaAsset.intendedFolderId())) {
            throw invalidArgument("Media asset " + mediaId + " was uploaded for a different folder.");
        }
        if (!mediaAsset.isPending()) {
            throw invalidArgument("Media asset " + mediaId + " has already been committed.");
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
            return mediaAssetStore.save(mediaAsset.commit(folderId, diaryEntryId, Instant.now(clock)));
        } catch (IllegalStateException exception) {
            throw invalidArgument(exception.getMessage());
        }
    }

    public BinaryMedia readAuthorizedBinary(UUID actorId, UUID mediaId) {
        MediaAsset mediaAsset = requireMedia(mediaId);
        if (mediaAsset.isPending()) {
            if (!mediaAsset.uploaderUserId().equals(actorId)) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Only the uploader can read this pending media asset.");
            }
        } else if (collaborationStore != null) {
            collaborationStore.findMembership(mediaAsset.committedFolderId(), actorId)
                    .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "Only folder members can read this media asset."));
        } else if (!mediaAsset.uploaderUserId().equals(actorId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the uploader can read this media asset.");
        }
        return new BinaryMedia(mediaAsset, readBlob(mediaAsset));
    }

    public int cleanupExpiredPending() {
        if (fileMediaAssetStore == null) {
            return 0;
        }
        Instant now = Instant.now(clock);
        int removed = 0;
        for (MediaAsset mediaAsset : fileMediaAssetStore.listAssets()) {
            if (mediaAsset.isExpired(now)) {
                fileMediaAssetStore.deleteBlob(mediaAsset.storageKey());
                fileMediaAssetStore.deleteAsset(mediaAsset.mediaId());
                removed++;
            }
        }
        return removed;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw invalidArgument("Could not read uploaded file.");
        }
    }

    private Dimensions readDimensions(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw invalidArgument("Uploaded file is not a readable image.");
            }
            return new Dimensions(image.getWidth(), image.getHeight());
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

    private void persistBlob(String storageKey, byte[] bytes) {
        if (fileMediaAssetStore == null) {
            return;
        }
        try {
            Path blobPath = fileMediaAssetStore.blobPath(storageKey);
            Files.createDirectories(blobPath.getParent());
            Files.write(blobPath, bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist media blob " + storageKey, exception);
        }
    }

    private byte[] readBlob(MediaAsset mediaAsset) {
        if (fileMediaAssetStore == null) {
            return new byte[0];
        }
        Path blobPath = fileMediaAssetStore.blobPath(mediaAsset.storageKey());
        if (!Files.isRegularFile(blobPath)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Media blob for " + mediaAsset.mediaId() + " was not found.");
        }
        try {
            return Files.readAllBytes(blobPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read media blob " + mediaAsset.mediaId(), exception);
        }
    }

    private void requirePersistedBlob(MediaAsset mediaAsset) {
        if (fileMediaAssetStore == null) {
            return;
        }
        byte[] bytes = readBlob(mediaAsset);
        if (bytes.length == 0) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Media blob for " + mediaAsset.mediaId() + " was not found.");
        }
        String mimeType = detectMimeType(bytes);
        if (!mimeType.equals(mediaAsset.mimeType())
                || bytes.length != mediaAsset.sizeBytes()
                || !checksumSha256(bytes).equals(mediaAsset.checksumSha256())) {
            throw invalidArgument("Media asset metadata does not match its persisted blob.");
        }
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

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private DomainException invalidArgument(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }

    public record BinaryMedia(MediaAsset mediaAsset, byte[] bytes) {
    }

    public record CleanupResult(int removedPendingMedia) {
    }

    private record Dimensions(Integer width, Integer height) {
    }
}
