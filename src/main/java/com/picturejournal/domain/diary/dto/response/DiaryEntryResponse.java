package com.picturejournal.domain.diary.dto.response;

import com.picturejournal.domain.diary.entity.DiaryEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 사진 일기 API 결과를 직렬화하는 DiaryEntryResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param entryId 사진 일기의 고유 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param authorUserId 일기를 작성한 사용자 식별자
 * @param mediaId 미디어 자산의 고유 식별자
 * @param title 공유 원문 또는 일기의 제목
 * @param body 사진 일기의 본문
 * @param placeName 사진 일기에 기록할 장소 이름
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param capturedAt 사진이 촬영되었거나 일기로 기록된 기준 시각
 * @param visibilityMode 객체 저장소 파일의 공개 범위 설정
 * @param tags 사진 일기를 분류하는 태그 목록
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record DiaryEntryResponse(
        UUID entryId,
        UUID folderId,
        UUID authorUserId,
        UUID mediaId,
        String title,
        String body,
        String placeName,
        double latitude,
        double longitude,
        Instant capturedAt,
        String visibilityMode,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt) {

    public static DiaryEntryResponse from(DiaryEntry entry) {
        return new DiaryEntryResponse(
                entry.entryId(),
                entry.folderId(),
                entry.authorUserId(),
                entry.mediaId(),
                entry.title(),
                entry.body(),
                entry.placeName(),
                entry.latitude(),
                entry.longitude(),
                entry.capturedAt(),
                entry.visibilityMode(),
                entry.tags(),
                entry.createdAt(),
                entry.updatedAt());
    }
}
