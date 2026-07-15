package com.picturejournal.domain.collaboration.dto.response;

import com.picturejournal.domain.collaboration.dto.internal.InviteView;
import com.picturejournal.domain.collaboration.vo.FolderInviteStatus;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import java.time.Instant;
import java.util.UUID;

/**
 * 협업 API 결과를 직렬화하는 InviteResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param inviteId 폴더 초대의 고유 식별자
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param token 인증 또는 초대 확인에 사용하는 토큰
 * @param role 폴더 멤버에게 부여된 권한 역할
 * @param status 현재 처리 단계 또는 생명주기 상태
 * @param createdBy 초대를 생성한 사용자 식별자
 * @param acceptedBy 초대를 실제로 수락한 사용자 식별자
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 * @param acceptedAt 초대가 수락된 시각
 */
public record InviteResponse(
        UUID inviteId,
        UUID folderId,
        String token,
        FolderRole role,
        FolderInviteStatus status,
        UUID createdBy,
        UUID acceptedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant acceptedAt) {

    public static InviteResponse from(InviteView inviteView) {
        return new InviteResponse(
                inviteView.inviteId(),
                inviteView.folderId(),
                inviteView.token(),
                inviteView.role(),
                inviteView.status(),
                inviteView.createdBy(),
                inviteView.acceptedBy(),
                inviteView.createdAt(),
                inviteView.updatedAt(),
                inviteView.acceptedAt());
    }
}
