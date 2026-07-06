package com.picturejournal.place.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SavedPlace(
        UUID placeId,
        UUID folderId,
        UUID creatorUserId,
        UUID shareIntakeId,
        String name,
        String category,
        String address,
        String regionText,
        Double latitude,
        Double longitude,
        String summary,
        String whyRecommended,
        List<String> keywords,
        VisitStatus visitStatus,
        Instant savedAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public SavedPlace {
        Objects.requireNonNull(placeId, "placeId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(creatorUserId, "creatorUserId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(keywords, "keywords must not be null");
        Objects.requireNonNull(visitStatus, "visitStatus must not be null");
        Objects.requireNonNull(savedAt, "savedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        keywords = List.copyOf(keywords);
    }

    public SavedPlace update(
            String nextName,
            String nextCategory,
            String nextAddress,
            String nextRegionText,
            Double nextLatitude,
            Double nextLongitude,
            String nextSummary,
            String nextWhyRecommended,
            List<String> nextKeywords,
            VisitStatus nextVisitStatus,
            Instant now) {
        return new SavedPlace(placeId, folderId, creatorUserId, shareIntakeId, nextName, nextCategory, nextAddress,
                nextRegionText, nextLatitude, nextLongitude, nextSummary, nextWhyRecommended, nextKeywords,
                nextVisitStatus, savedAt, now, resolvedAt);
    }
}
