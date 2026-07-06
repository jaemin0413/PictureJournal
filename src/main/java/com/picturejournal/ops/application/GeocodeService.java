package com.picturejournal.ops.application;

import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public record PlaceSearchCandidate(String provider, String name, Double latitude, Double longitude, double confidence) {
    }

    public record SearchResult(String query, boolean cached, List<PlaceSearchCandidate> candidates) {
    }

    public record ReverseResult(double latitude, double longitude, boolean cached, String address) {
    }

    public record GeocodeDiagnostics(int searchCacheSize, int reverseCacheSize, int missLimitPerWindow, int missesInWindow, Instant windowStartedAt) {
    }
}
