package com.picturejournal.domain.place.dto.response;

import com.picturejournal.domain.place.dto.internal.ShareIntakeView;
import com.picturejournal.domain.place.entity.ShareIntakeItem;
import com.picturejournal.domain.place.vo.ShareIntakeStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 장소 API 결과를 직렬화하는 ShareIntakeResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
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
 * @param candidates 수집 원문에서 추출된 장소 후보 목록
 * @param resolvedPlace 이미 확정된 장소이며 미확정 상태에서는 null
 */
public record ShareIntakeResponse(
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
        Instant resolvedAt,
        List<PlaceCandidateResponse> candidates,
        SavedPlaceResponse resolvedPlace) {

    public static ShareIntakeResponse from(ShareIntakeView view) {
        ShareIntakeItem intake = view.intake();
        return new ShareIntakeResponse(
                intake.intakeId(), intake.folderId(), intake.receivedByUserId(), intake.sourceApp(), intake.platform(),
                intake.receivedVia(), intake.rawUrl(), intake.rawTitle(), intake.rawText(), intake.normalizedUrl(), intake.status(),
                intake.failureReason(), intake.resolvedPlaceId(), intake.receivedAt(), intake.updatedAt(), intake.resolvedAt(),
                view.candidates().stream().map(PlaceCandidateResponse::from).toList(),
                view.resolvedPlace() == null ? null : SavedPlaceResponse.from(view.resolvedPlace()));
    }
}
