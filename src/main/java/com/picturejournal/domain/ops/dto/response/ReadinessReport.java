package com.picturejournal.domain.ops.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * 운영 API 결과를 직렬화하는 ReadinessReport 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param checkedAt 운영 준비 상태를 점검한 시각
 * @param ready 필수 운영 조건이 모두 충족됐는지 여부
 * @param requiredHomeServerEnvKeys 홈 서버 실행에 필요한 환경 변수 이름 목록
 * @param missingHomeServerEnvKeys 설정되지 않은 필수 환경 변수 이름 목록
 * @param unresolvedShareIntakeCount 아직 저장 장소로 확정되지 않은 공유 수집 항목 수
 * @param geocode 지오코딩 캐시와 호출 제한 진단 정보
 */
public record ReadinessReport(
        Instant checkedAt,
        boolean ready,
        List<String> requiredHomeServerEnvKeys,
        List<String> missingHomeServerEnvKeys,
        long unresolvedShareIntakeCount,
        GeocodeDiagnostics geocode) {
}
