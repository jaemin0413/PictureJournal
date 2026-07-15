package com.picturejournal.domain.share.dto.response;

import com.picturejournal.domain.share.entity.ShareSpikeDraft;
import java.time.Instant;
import java.util.UUID;

/**
 * 외부 공유 API 결과를 직렬화하는 ShareSpikeDraftResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param draftId 외부 공유 임시 초안의 식별자
 * @param status 현재 처리 단계 또는 생명주기 상태
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param sourceApp 공유 데이터를 전달한 원본 애플리케이션
 * @param platform 공유가 발생한 클라이언트 플랫폼
 * @param rawUrl 가공 전 공유 URL
 * @param rawTitle 가공 전 공유 제목
 * @param rawText 가공 전 공유 본문
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record ShareSpikeDraftResponse(
        UUID draftId,
        String status,
        UUID actorId,
        UUID folderId,
        String sourceApp,
        String platform,
        String rawUrl,
        String rawTitle,
        String rawText,
        Instant createdAt,
        Instant updatedAt) {

    public static ShareSpikeDraftResponse from(ShareSpikeDraft draft) {
        return new ShareSpikeDraftResponse(
                draft.draftId(),
                draft.status().name(),
                draft.actorId(),
                draft.folderId(),
                draft.payload().sourceApp(),
                draft.payload().platform(),
                draft.payload().rawUrl(),
                draft.payload().rawTitle(),
                draft.payload().rawText(),
                draft.createdAt(),
                draft.updatedAt());
    }
}
