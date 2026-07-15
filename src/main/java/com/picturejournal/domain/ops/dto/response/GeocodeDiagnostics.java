package com.picturejournal.domain.ops.dto.response;

import java.time.Instant;

/**
 * 운영 API 결과를 직렬화하는 GeocodeDiagnostics 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param searchCacheSize 현재 검색 지오코딩 캐시에 저장된 항목 수
 * @param reverseCacheSize 현재 역지오코딩 캐시에 저장된 항목 수
 * @param missLimitPerWindow 하나의 제한 구간에서 허용하는 최대 캐시 미스 수
 * @param missesInWindow 현재 제한 구간에서 발생한 캐시 미스 수
 * @param windowStartedAt 현재 호출 제한 구간이 시작된 시각
 */
public record GeocodeDiagnostics(int searchCacheSize, int reverseCacheSize, int missLimitPerWindow, int missesInWindow, Instant windowStartedAt) {
}
