package com.picturejournal.domain.ops.dto.response;

/**
 * 운영 API 결과를 직렬화하는 PlaceSearchCandidate 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param provider 장소 후보를 만든 지오코딩 공급자 또는 규칙 이름
 * @param name 사용자에게 표시할 이름
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param confidence 후보 추출 결과의 신뢰도 점수
 */
public record PlaceSearchCandidate(String provider, String name, Double latitude, Double longitude, double confidence) {
}
