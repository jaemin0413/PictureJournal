package com.picturejournal.domain.share.vo;

/**
 * 외부 공유 도메인에서 값의 의미와 허용 범위를 표현하는 SharePayload 값 타입이다.
 * 관련 값을 하나의 변경 불가능한 값 객체로 묶는다.
 *
 * @param sourceApp 공유 데이터를 전달한 원본 애플리케이션
 * @param platform 공유가 발생한 클라이언트 플랫폼
 * @param rawUrl 가공 전 공유 URL
 * @param rawTitle 가공 전 공유 제목
 * @param rawText 가공 전 공유 본문
 */
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
