package com.picturejournal.media.application;

import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("image/jpeg", "image/png");

    private final MediaAssetStore mediaAssetStore;
    private final FileMediaAssetStore fileMediaAssetStore;
    private final ExifMetadataExtractor exifMetadataExtractor;
    private final Clock clock;

    @Autowired
    public MediaService(MediaAssetStore mediaAssetStore, ExifMetadataExtractor exifMetadataExtractor) {
        this(mediaAssetStore, exifMetadataExtractor, Clock.systemUTC());
    }

    MediaService(MediaAssetStore mediaAssetStore, ExifMetadataExtractor exifMetadataExtractor, Clock clock) {
        this.mediaAssetStore = mediaAssetStore;
        this.fileMediaAssetStore = mediaAssetStore instanceof FileMediaAssetStore fileStore ? fileStore : null;
        this.exifMetadataExtractor = exifMetadataExtractor;
        this.clock = clock;
    }

    public MediaAsset uploadDirect(UUID actorId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidArgument("file is required.");
        }
        String mimeType = normalizeMimeType(file.getContentType());
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw invalidArgument("Only image/jpeg and image/png files are supported.");
        }
        byte[] bytes = readBytes(file);
        Dimensions dimensions = readDimensions(bytes);
        ExifMetadataExtractor.ExtractedExif exif = exifMetadataExtractor.extract(bytes);
        UUID mediaId = UUID.randomUUID();
        String storageKey = mediaId + extensionFor(mimeType);
        persistBlob(storageKey, bytes);
        MediaAsset mediaAsset = new MediaAsset(
                mediaId,
                actorId,
                storageKey,
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
                Instant.now(clock));
        return mediaAssetStore.save(mediaAsset);
    }

    public MediaAsset requireMedia(UUID mediaId) {
        return mediaAssetStore.findById(mediaId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Media asset " + mediaId + " was not found."));
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

    private record Dimensions(Integer width, Integer height) {
    }
}
