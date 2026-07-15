package com.picturejournal.domain.media.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 미디어 도메인의 상태와 식별자를 표현하는 MediaAsset 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param mediaId 미디어 자산의 고유 식별자
 * @param uploaderUserId 미디어 바이너리를 업로드한 사용자 식별자
 * @param storageKey 바이너리를 찾기 위한 저장소 내부 키
 * @param originalFilename 업로드 당시의 원본 파일명
 * @param mimeType 검증된 미디어 MIME 타입
 * @param sizeBytes 업로드 바이너리 크기(바이트)
 * @param width 이미지 가로 픽셀 수
 * @param height 이미지 세로 픽셀 수
 * @param exifJson 추출한 EXIF 메타데이터의 JSON 표현
 * @param takenAt EXIF에서 확인한 실제 촬영 시각
 * @param cameraMake EXIF 카메라 제조사
 * @param cameraModel EXIF 카메라 모델
 * @param gpsLatitude EXIF에서 추출한 GPS 위도
 * @param gpsLongitude EXIF에서 추출한 GPS 경도
 * @param createdAt 레코드가 처음 생성된 시각
 */
public record MediaAsset(
        UUID mediaId,
        UUID uploaderUserId,
        String storageKey,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        Integer width,
        Integer height,
        String exifJson,
        Instant takenAt,
        String cameraMake,
        String cameraModel,
        Double gpsLatitude,
        Double gpsLongitude,
        Instant createdAt) {

    public MediaAsset {
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(uploaderUserId, "uploaderUserId must not be null");
        Objects.requireNonNull(storageKey, "storageKey must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }
}
