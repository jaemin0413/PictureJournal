package com.picturejournal.domain.diary.dto.response;

import com.picturejournal.domain.diary.entity.DiaryEntry;
import java.time.Instant;
import java.util.UUID;

/**
 * 사진 일기 API 결과를 직렬화하는 DiaryMapEntryResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param entryId 사진 일기의 고유 식별자
 * @param title 공유 원문 또는 일기의 제목
 * @param placeName 사진 일기에 기록할 장소 이름
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param capturedAt 사진이 촬영되었거나 일기로 기록된 기준 시각
 */
public record DiaryMapEntryResponse(
        UUID entryId,
        String title,
        String placeName,
        double latitude,
        double longitude,
        Instant capturedAt) {

    public static DiaryMapEntryResponse from(DiaryEntry entry) {
        return new DiaryMapEntryResponse(
                entry.entryId(),
                entry.title(),
                entry.placeName(),
                entry.latitude(),
                entry.longitude(),
                entry.capturedAt());
    }
}
