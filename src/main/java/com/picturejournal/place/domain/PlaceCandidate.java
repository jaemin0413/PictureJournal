package com.picturejournal.place.domain;

import java.util.Objects;
import java.util.UUID;

public record PlaceCandidate(
        UUID candidateId,
        UUID intakeId,
        String provider,
        String name,
        String address,
        Double latitude,
        Double longitude,
        double confidence,
        String rawPayloadJson) {

    public PlaceCandidate {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(intakeId, "intakeId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
