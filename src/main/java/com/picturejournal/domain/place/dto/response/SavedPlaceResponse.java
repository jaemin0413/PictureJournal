package com.picturejournal.domain.place.dto.response;

import com.picturejournal.domain.place.entity.SavedPlace;
import com.picturejournal.domain.place.vo.VisitStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 장소 API 결과를 직렬화하는 SavedPlaceResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
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
public record SavedPlaceResponse(
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

    public static SavedPlaceResponse from(SavedPlace place) {
        return new SavedPlaceResponse(place.placeId(), place.folderId(), place.creatorUserId(), place.shareIntakeId(), place.name(),
                place.category(), place.address(), place.regionText(), place.latitude(), place.longitude(), place.summary(),
                place.whyRecommended(), place.keywords(), place.visitStatus(), place.savedAt(), place.updatedAt(), place.resolvedAt());
    }
}
