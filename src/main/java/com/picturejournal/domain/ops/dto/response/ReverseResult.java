package com.picturejournal.domain.ops.dto.response;

/**
 * 운영 API 결과를 직렬화하는 ReverseResult 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param cached 결과가 캐시에서 반환됐는지 여부
 * @param address 사용자에게 표시할 주소
 */
public record ReverseResult(double latitude, double longitude, boolean cached, String address) {
}
