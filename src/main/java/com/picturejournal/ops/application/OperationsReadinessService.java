package com.picturejournal.ops.application;

import com.picturejournal.place.application.PlaceStore;
import com.picturejournal.place.domain.ShareIntakeStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

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

    public record ReadinessReport(
            Instant checkedAt,
            boolean ready,
            List<String> requiredHomeServerEnvKeys,
            List<String> missingHomeServerEnvKeys,
            long unresolvedShareIntakeCount,
            GeocodeService.GeocodeDiagnostics geocode) {
    }
}
