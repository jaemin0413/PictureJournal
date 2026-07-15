package com.picturejournal.domain.ops.dto.response;

import java.util.List;

/**
 * 운영 API 결과를 직렬화하는 SearchResult 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param query 정규화 전 사용자의 장소 검색어
 * @param cached 결과가 캐시에서 반환됐는지 여부
 * @param candidates 수집 원문에서 추출된 장소 후보 목록
 */
public record SearchResult(String query, boolean cached, List<PlaceSearchCandidate> candidates) {
}
