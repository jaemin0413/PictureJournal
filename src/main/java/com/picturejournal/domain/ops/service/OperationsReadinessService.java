package com.picturejournal.domain.ops.service;

import com.picturejournal.domain.ops.dto.response.ReadinessReport;
import com.picturejournal.domain.place.repository.PlaceStore;
import com.picturejournal.domain.place.vo.ShareIntakeStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 서버가 실제 운영 요청을 받을 준비가 됐는지 여러 신호를 모아 판단한다.
 *
 * <p>홈 서버 실행에 필요한 환경 변수의 누락 여부, 아직 해결되지 않은 공유 수집 항목 수,
 * 지오코딩 캐시와 호출 제한 진단 정보를 한 응답으로 조합한다. 준비 여부는 필수 환경 변수가
 * 모두 존재할 때만 {@code true}가 된다.</p>
 */
@Service
public class OperationsReadinessService {

    private static final List<String> HOME_SERVER_ENV_KEYS = List.of(
            "DB_URL",
            "DB_USERNAME",
            "DB_PASSWORD",
            "STORAGE_PROVIDER",
            "STORAGE_BUCKET",
            "STORAGE_ENDPOINT",
            "STORAGE_ACCESS_KEY",
            "STORAGE_SECRET_KEY");

    private final PlaceStore placeStore;
    private final GeocodeService geocodeService;

    public OperationsReadinessService(PlaceStore placeStore, GeocodeService geocodeService) {
        this.placeStore = placeStore;
        this.geocodeService = geocodeService;
    }

    /**
     * 환경 변수, 미처리 공유 항목과 지오코딩 상태를 종합해 준비 상태를 만든다.
     *
     * @return 현재 운영 준비 여부와 세부 점검 결과
     */
    public ReadinessReport report() {
        List<String> missingEnvKeys = HOME_SERVER_ENV_KEYS.stream()
                .filter(key -> System.getenv(key) == null || System.getenv(key).isBlank())
                .toList();
        long unresolvedIntakes = placeStore.listIntakes().stream()
                .filter(intake -> intake.status() != ShareIntakeStatus.RESOLVED)
                .count();
        return new ReadinessReport(
                Instant.now(),
                missingEnvKeys.isEmpty() && unresolvedIntakes == 0,
                HOME_SERVER_ENV_KEYS,
                missingEnvKeys,
                unresolvedIntakes,
                geocodeService.diagnostics());
    }
}
