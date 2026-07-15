package com.picturejournal.domain.media.dto.internal;

import java.time.Instant;

/**
 * 미디어 계층 사이에서 값을 전달하는 ExtractedExif 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param exifJson 추출한 EXIF 메타데이터의 JSON 표현
 * @param cameraMake EXIF 카메라 제조사
 * @param cameraModel EXIF 카메라 모델
 * @param takenAt EXIF에서 확인한 실제 촬영 시각
 * @param gpsLatitude EXIF에서 추출한 GPS 위도
 * @param gpsLongitude EXIF에서 추출한 GPS 경도
 */
public record ExtractedExif(
        String exifJson,
        String cameraMake,
        String cameraModel,
        Instant takenAt,
        Double gpsLatitude,
        Double gpsLongitude) {

    public static ExtractedExif empty() {
        return new ExtractedExif("{}", null, null, null, null, null);
    }
}
