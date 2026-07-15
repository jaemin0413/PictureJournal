package com.picturejournal.domain.place.entity;

import com.picturejournal.domain.place.vo.ShareIntakeStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 장소 도메인의 상태와 식별자를 표현하는 ShareIntakeItem 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param intakeId 외부 공유 수집 항목의 고유 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param receivedByUserId 외부 공유 데이터를 최초로 받은 사용자 식별자
 * @param sourceApp 공유 데이터를 전달한 원본 애플리케이션
 * @param platform 공유가 발생한 클라이언트 플랫폼
 * @param receivedVia 공유 데이터가 유입된 경로
 * @param rawUrl 가공 전 공유 URL
 * @param rawTitle 가공 전 공유 제목
 * @param rawText 가공 전 공유 본문
 * @param normalizedUrl 공유 원문에서 추출하고 정규화한 URL
 * @param status 현재 처리 단계 또는 생명주기 상태
 * @param failureReason 자동 처리를 완료하지 못한 이유
 * @param resolvedPlaceId 수집 항목에서 생성된 저장 장소 식별자
 * @param receivedAt 외부 공유 데이터가 서버에 도착한 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 * @param resolvedAt 수집 항목이 저장 장소로 확정된 시각
 */
public record ShareIntakeItem(
        UUID intakeId,
        UUID folderId,
        UUID receivedByUserId,
        String sourceApp,
        String platform,
        String receivedVia,
        String rawUrl,
        String rawTitle,
        String rawText,
        String normalizedUrl,
        ShareIntakeStatus status,
        String failureReason,
        UUID resolvedPlaceId,
        Instant receivedAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public ShareIntakeItem {
        Objects.requireNonNull(intakeId, "intakeId must not be null");
        Objects.requireNonNull(receivedByUserId, "receivedByUserId must not be null");
        Objects.requireNonNull(sourceApp, "sourceApp must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(receivedVia, "receivedVia must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static ShareIntakeItem create(
            UUID intakeId,
            UUID folderId,
            UUID actorId,
            String sourceApp,
            String platform,
            String receivedVia,
            String rawUrl,
            String rawTitle,
            String rawText,
            String normalizedUrl,
            ShareIntakeStatus status,
            String failureReason,
            Instant now) {
        return new ShareIntakeItem(intakeId, folderId, actorId, sourceApp, platform, receivedVia, rawUrl, rawTitle, rawText,
                normalizedUrl, status, failureReason, null, now, now, null);
    }

    /**
     * 확정 전 수집 항목을 임시 저장 상태로 변경한다.
     */
    public ShareIntakeItem saveDraft(Instant now) {
        return new ShareIntakeItem(intakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, ShareIntakeStatus.DRAFT, failureReason, resolvedPlaceId, receivedAt, now, resolvedAt);
    }

    public ShareIntakeItem resolve(UUID placeId, Instant now) {
        return new ShareIntakeItem(intakeId, folderId, receivedByUserId, sourceApp, platform, receivedVia, rawUrl, rawTitle,
                rawText, normalizedUrl, ShareIntakeStatus.RESOLVED, failureReason, placeId, receivedAt, now, now);
    }
}
