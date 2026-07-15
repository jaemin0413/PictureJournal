package com.picturejournal.domain.ops.service;

import com.picturejournal.domain.ops.dto.response.GeocodeDiagnostics;
import com.picturejournal.domain.ops.dto.response.PlaceSearchCandidate;
import com.picturejournal.domain.ops.dto.response.ReverseResult;
import com.picturejournal.domain.ops.dto.response.SearchResult;
import com.picturejournal.global.error.ErrorCode;
import com.picturejournal.global.exception.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 장소 검색과 좌표 역검색을 제공하는 경량 지오코딩 어댑터다.
 *
 * <p>동일 입력은 TTL 동안 메모리 캐시에서 반환하고 캐시 미스만 호출 제한에 반영한다.
 * 현재 구현은 외부 공급자 대신 재현 가능한 규칙 기반 결과를 생성하므로 로컬 개발과
 * 운영 준비 상태 점검에 사용할 수 있다.</p>
 *
 * <p>{@link #diagnostics()}는 캐시 크기와 현재 제한 구간의 미스 수를 노출한다.</p>
 */
@Service
public class GeocodeService {

    private static final int MISS_LIMIT_PER_WINDOW = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, CachedSearch> searchCache = new LinkedHashMap<>();
    private final Map<String, CachedReverse> reverseCache = new LinkedHashMap<>();
    private Instant windowStartedAt;
    private int missesInWindow;

    @Autowired
    public GeocodeService() {
        this(Clock.systemUTC());
    }

    GeocodeService(Clock clock) {
        this.clock = clock;
        this.windowStartedAt = Instant.now(clock);
    }

    /**
     * 검색어를 정규화하고 캐시 또는 규칙 기반 지오코딩 결과를 반환한다.
     *
     * @param query 정규화할 장소 검색어
     * @return 캐시 사용 여부와 장소 후보를 포함한 검색 결과
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public synchronized SearchResult search(String query) {
        String normalized = normalizeRequired(query, "q").toLowerCase(Locale.ROOT);
        CachedSearch cached = searchCache.get(normalized);
        if (cached != null) {
            return new SearchResult(normalized, true, cached.candidates());
        }
        assertMissAllowed();
        List<PlaceSearchCandidate> candidates = List.of(new PlaceSearchCandidate("local-dev", query.trim(), null, null, 0.5));
        searchCache.put(normalized, new CachedSearch(candidates, Instant.now(clock)));
        return new SearchResult(normalized, false, candidates);
    }

    /**
     * 좌표를 검증하고 캐시 또는 규칙 기반 역지오코딩 결과를 반환한다.
     *
     * @param latitude 역지오코딩할 위도
     * @param longitude 역지오코딩할 경도
     * @return 캐시 사용 여부와 주소를 포함한 역지오코딩 결과
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public synchronized ReverseResult reverse(double latitude, double longitude) {
        validateLocation(latitude, longitude);
        String key = String.format(Locale.ROOT, "%.5f,%.5f", latitude, longitude);
        CachedReverse cached = reverseCache.get(key);
        if (cached != null) {
            return new ReverseResult(latitude, longitude, true, cached.address());
        }
        assertMissAllowed();
        String address = "Approximate address for " + key;
        reverseCache.put(key, new CachedReverse(address, Instant.now(clock)));
        return new ReverseResult(latitude, longitude, false, address);
    }

    /**
     * 현재 지오코딩 캐시와 호출 제한 상태를 반환한다.
     *
     * @return 캐시 크기와 현재 호출 제한 진단 정보
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public synchronized GeocodeDiagnostics diagnostics() {
        resetWindowIfNeeded();
        return new GeocodeDiagnostics(searchCache.size(), reverseCache.size(), MISS_LIMIT_PER_WINDOW, missesInWindow, windowStartedAt);
    }

    private void assertMissAllowed() {
        resetWindowIfNeeded();
        if (missesInWindow >= MISS_LIMIT_PER_WINDOW) {
            throw new DomainException(ErrorCode.RATE_LIMITED, "Geocode provider throttle exceeded; retry after cache or backoff window.");
        }
        missesInWindow++;
    }

    private void resetWindowIfNeeded() {
        Instant now = Instant.now(clock);
        if (Duration.between(windowStartedAt, now).compareTo(WINDOW) >= 0) {
            windowStartedAt = now;
            missesInWindow = 0;
        }
    }

    private void validateLocation(double latitude, double longitude) {
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "latitude or longitude is out of range.");
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, fieldName + " is required.");
        }
        return value.trim();
    }

    private record CachedSearch(List<PlaceSearchCandidate> candidates, Instant cachedAt) {
    }

    private record CachedReverse(String address, Instant cachedAt) {
    }
}
