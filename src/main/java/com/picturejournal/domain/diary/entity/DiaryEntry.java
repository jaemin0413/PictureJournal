package com.picturejournal.domain.diary.entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 사진 일기 도메인의 상태와 식별자를 표현하는 DiaryEntry 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
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
public record DiaryEntry(
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

    public DiaryEntry {
        Objects.requireNonNull(entryId, "entryId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(authorUserId, "authorUserId must not be null");
        Objects.requireNonNull(mediaId, "mediaId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(visibilityMode, "visibilityMode must not be null");
        Objects.requireNonNull(tags, "tags must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        tags = List.copyOf(tags);
    }

    public static DiaryEntry create(
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
            List<String> tags,
            Instant now) {
        return new DiaryEntry(entryId, folderId, authorUserId, mediaId, title, body, placeName, latitude, longitude,
                capturedAt, "folder_members", tags, now, now);
    }

    public DiaryEntry update(
            String nextTitle,
            String nextBody,
            String nextPlaceName,
            double nextLatitude,
            double nextLongitude,
            Instant nextCapturedAt,
            List<String> nextTags,
            Instant now) {
        return new DiaryEntry(entryId, folderId, authorUserId, mediaId, nextTitle, nextBody, nextPlaceName,
                nextLatitude, nextLongitude, nextCapturedAt, visibilityMode, nextTags, createdAt, now);
    }
}
