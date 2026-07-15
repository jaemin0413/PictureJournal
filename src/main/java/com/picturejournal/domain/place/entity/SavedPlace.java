package com.picturejournal.domain.place.entity;

import com.picturejournal.domain.place.vo.VisitStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 장소 도메인의 상태와 식별자를 표현하는 SavedPlace 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param placeId 저장 장소의 고유 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param creatorUserId 저장 장소를 만든 사용자 식별자
 * @param shareIntakeId 이 장소를 생성한 원본 공유 수집 항목 식별자
 * @param name 사용자에게 표시할 이름
 * @param category 장소를 구분하는 사용자 지정 분류
 * @param address 사용자에게 표시할 주소
 * @param regionText 검색과 분류에 사용할 지역 문자열
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param summary 장소에 대한 간략한 설명
 * @param whyRecommended 장소를 저장하거나 추천한 이유
 * @param keywords 검색과 필터링에 사용할 중복 제거 키워드 목록
 * @param visitStatus 장소 방문 계획 또는 완료 상태
 * @param savedAt 사용자가 수집 항목을 임시 저장한 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 * @param resolvedAt 수집 항목이 저장 장소로 확정된 시각
 */
public record SavedPlace(
        UUID placeId,
        UUID folderId,
        UUID creatorUserId,
        UUID shareIntakeId,
        String name,
        String category,
        String address,
        String regionText,
        Double latitude,
        Double longitude,
        String summary,
        String whyRecommended,
        List<String> keywords,
        VisitStatus visitStatus,
        Instant savedAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public SavedPlace {
        Objects.requireNonNull(placeId, "placeId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(creatorUserId, "creatorUserId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(keywords, "keywords must not be null");
        Objects.requireNonNull(visitStatus, "visitStatus must not be null");
        Objects.requireNonNull(savedAt, "savedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        keywords = List.copyOf(keywords);
    }

    public SavedPlace update(
            String nextName,
            String nextCategory,
            String nextAddress,
            String nextRegionText,
            Double nextLatitude,
            Double nextLongitude,
            String nextSummary,
            String nextWhyRecommended,
            List<String> nextKeywords,
            VisitStatus nextVisitStatus,
            Instant now) {
        return new SavedPlace(placeId, folderId, creatorUserId, shareIntakeId, nextName, nextCategory, nextAddress,
                nextRegionText, nextLatitude, nextLongitude, nextSummary, nextWhyRecommended, nextKeywords,
                nextVisitStatus, savedAt, now, resolvedAt);
    }
}
