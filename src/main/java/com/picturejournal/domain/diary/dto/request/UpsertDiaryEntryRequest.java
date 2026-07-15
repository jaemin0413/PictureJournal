package com.picturejournal.domain.diary.dto.request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 사진 일기 API 입력 값을 전달하는 UpsertDiaryEntryRequest 요청 DTO다.
 * 요청 시점의 입력 묶음을 변경 불가능한 값으로 전달한다.
 *
 * @param mediaId 미디어 자산의 고유 식별자
 * @param title 공유 원문 또는 일기의 제목
 * @param body 사진 일기의 본문
 * @param placeName 사진 일기에 기록할 장소 이름
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param capturedAt 사진이 촬영되었거나 일기로 기록된 기준 시각
 * @param tags 사진 일기를 분류하는 태그 목록
 */
public record UpsertDiaryEntryRequest(
        UUID mediaId,
        String title,
        String body,
        String placeName,
        Double latitude,
        Double longitude,
        Instant capturedAt,
        List<String> tags) {
}
