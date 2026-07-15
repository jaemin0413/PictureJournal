package com.picturejournal.domain.place.dto.internal;

import java.util.UUID;

/**
 * 장소 계층 사이에서 값을 전달하는 CreateShareIntakeCommand 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param rawUrl 가공 전 공유 URL
 * @param rawTitle 가공 전 공유 제목
 * @param rawText 가공 전 공유 본문
 * @param sourceApp 공유 데이터를 전달한 원본 애플리케이션
 * @param platform 공유가 발생한 클라이언트 플랫폼
 * @param receivedVia 공유 데이터가 유입된 경로
 */
public record CreateShareIntakeCommand(
        UUID folderId,
        String rawUrl,
        String rawTitle,
        String rawText,
        String sourceApp,
        String platform,
        String receivedVia) {
}
