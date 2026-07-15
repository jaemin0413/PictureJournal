package com.picturejournal.domain.share.dto.request;

import java.util.UUID;

/**
 * 외부 공유 API 입력 값을 전달하는 CreateShareSpikeDraftRequest 요청 DTO다.
 * 요청 시점의 입력 묶음을 변경 불가능한 값으로 전달한다.
 *
 * @param sourceApp 공유 데이터를 전달한 원본 애플리케이션
 * @param platform 공유가 발생한 클라이언트 플랫폼
 * @param rawUrl 가공 전 공유 URL
 * @param rawTitle 가공 전 공유 제목
 * @param rawText 가공 전 공유 본문
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 */
public record CreateShareSpikeDraftRequest(
        String sourceApp,
        String platform,
        String rawUrl,
        String rawTitle,
        String rawText,
        UUID actorId,
        UUID folderId) {
}
