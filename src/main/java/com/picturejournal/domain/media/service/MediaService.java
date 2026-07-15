package com.picturejournal.domain.media.service;

import com.picturejournal.domain.media.dto.internal.ExtractedExif;
import com.picturejournal.domain.media.entity.MediaAsset;
import com.picturejournal.domain.media.repository.FileMediaAssetStore;
import com.picturejournal.domain.media.repository.MediaAssetStore;
import com.picturejournal.global.error.ErrorCode;
import com.picturejournal.global.exception.DomainException;
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

/**
 * 업로드된 이미지의 바이너리와 메타데이터 생명주기를 관리한다.
 *
 * <p>직접 업로드 요청을 받으면 빈 파일, MIME 타입과 크기를 먼저 검증한다.
 * 이후 {@link ExifMetadataExtractor}로 촬영 시각·카메라·GPS 정보를 추출하고,
 * 저장소 키와 {@link MediaAsset} 메타데이터를 생성해 repository에 보관한다.</p>
 *
 * <p>미디어가 특정 폴더에 연결된 뒤에는 협업 도메인의 멤버십과 역할을 이용해
 * 읽기·쓰기 권한을 제한한다. 저장 중인 PENDING 미디어는 만료 시간 이후 정리 대상이 된다.</p>
 */
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

    /**
     * 업로드 파일을 검증하고 EXIF 정보를 추출한 뒤 바이너리와 메타데이터를 저장한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param file 검증하고 저장할 업로드 파일
     * @return 저장된 미디어 메타데이터
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public MediaAsset uploadDirect(UUID actorId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidArgument("file is required.");
        }
        String mimeType = normalizeMimeType(file.getContentType());
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw invalidArgument("Only image/jpeg and image/png files are supported.");
        }
        // 바이너리는 한 번만 읽고 같은 바이트 배열로 이미지 구조와 EXIF를 각각 분석한다.
        byte[] bytes = readBytes(file);
        Dimensions dimensions = readDimensions(bytes);
        ExtractedExif exif = exifMetadataExtractor.extract(bytes);
        UUID mediaId = UUID.randomUUID();
        String storageKey = mediaId + extensionFor(mimeType);
        // 바이너리를 먼저 기록해 메타데이터만 존재하고 실제 파일이 없는 상태를 피한다.
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

    /**
     * 식별자로 미디어를 조회하고 없으면 도메인 예외를 발생시킨다.
     *
     * @param mediaId 대상 미디어 식별자
     * @return 조회된 미디어 자산
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
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
