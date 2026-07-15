package com.picturejournal.domain.share.entity;

import com.picturejournal.domain.share.vo.SharePayload;
import com.picturejournal.domain.share.vo.ShareSpikeStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 외부 공유 도메인의 상태와 식별자를 표현하는 ShareSpikeDraft 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param draftId 외부 공유 임시 초안의 식별자
 * @param status 현재 처리 단계 또는 생명주기 상태
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param payload 원본 앱과 공유 텍스트를 묶은 변경 불가능한 페이로드
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record ShareSpikeDraft(
        UUID draftId,
        ShareSpikeStatus status,
        UUID actorId,
        UUID folderId,
        SharePayload payload,
        Instant createdAt,
        Instant updatedAt) {

    public static ShareSpikeDraft create(UUID draftId, UUID actorId, UUID folderId, SharePayload payload, Instant now) {
        return new ShareSpikeDraft(
                draftId,
                resolveStatus(actorId, folderId),
                actorId,
                folderId,
                payload,
                now,
                now);
    }

    /**
     * 로그인 완료 후 공유 초안에 사용자 식별자를 연결한다.
     */
    public ShareSpikeDraft bindActor(UUID nextActorId, Instant now) {
        return new ShareSpikeDraft(
                draftId,
                resolveStatus(nextActorId, folderId),
                nextActorId,
                folderId,
                payload,
                createdAt,
                now);
    }

    /**
     * 인증 사용자가 연결된 공유 초안에 대상 폴더를 지정한다.
     */
    public ShareSpikeDraft selectFolder(UUID nextFolderId, Instant now) {
        return new ShareSpikeDraft(
                draftId,
                resolveStatus(actorId, nextFolderId),
                actorId,
                nextFolderId,
                payload,
                createdAt,
                now);
    }

    private static ShareSpikeStatus resolveStatus(UUID actorId, UUID folderId) {
        if (actorId == null) {
            return ShareSpikeStatus.PENDING_AUTH;
        }
        if (folderId == null) {
            return ShareSpikeStatus.AWAITING_FOLDER_SELECTION;
        }
        return ShareSpikeStatus.READY_FOR_REVIEW;
    }
}
