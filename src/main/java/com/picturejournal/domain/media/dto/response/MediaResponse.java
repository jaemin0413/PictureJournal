package com.picturejournal.domain.media.dto.response;

import com.picturejournal.domain.media.entity.MediaAsset;
import java.time.Instant;
import java.util.UUID;

/**
 * 미디어 API 결과를 직렬화하는 MediaResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param mediaId 미디어 자산의 고유 식별자
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
public record MediaResponse(
        UUID mediaId,
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

    public static MediaResponse from(MediaAsset mediaAsset) {
        return new MediaResponse(
                mediaAsset.mediaId(),
                mediaAsset.storageKey(),
                mediaAsset.originalFilename(),
                mediaAsset.mimeType(),
                mediaAsset.sizeBytes(),
                mediaAsset.width(),
                mediaAsset.height(),
                mediaAsset.exifJson(),
                mediaAsset.takenAt(),
                mediaAsset.cameraMake(),
                mediaAsset.cameraModel(),
                mediaAsset.gpsLatitude(),
                mediaAsset.gpsLongitude(),
                mediaAsset.createdAt());
    }
}
