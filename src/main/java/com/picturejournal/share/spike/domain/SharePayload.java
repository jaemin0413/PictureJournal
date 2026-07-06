package com.picturejournal.share.spike.domain;

public record SharePayload(
        String sourceApp,
        String platform,
        String rawUrl,
        String rawTitle,
        String rawText) {

    public boolean hasAnyContent() {
        return hasText(rawUrl) || hasText(rawTitle) || hasText(rawText);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
