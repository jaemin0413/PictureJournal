package com.picturejournal.domain.share.dto.internal;

import java.util.UUID;

/**
 * 외부 공유 계층 사이에서 값을 전달하는 CreateShareSpikeDraftCommand 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param sourceApp 공유 데이터를 전달한 원본 애플리케이션
 * @param platform 공유가 발생한 클라이언트 플랫폼
 * @param rawUrl 가공 전 공유 URL
 * @param rawTitle 가공 전 공유 제목
 * @param rawText 가공 전 공유 본문
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 */
public record CreateShareSpikeDraftCommand(
        String sourceApp,
        String platform,
        String rawUrl,
        String rawTitle,
        String rawText,
        UUID actorId,
        UUID folderId) {
}
