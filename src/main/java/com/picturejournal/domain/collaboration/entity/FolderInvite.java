package com.picturejournal.domain.collaboration.entity;

import com.picturejournal.domain.collaboration.vo.FolderInviteStatus;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 협업 도메인의 상태와 식별자를 표현하는 FolderInvite 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
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
public record FolderInvite(
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

    public FolderInvite {
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static FolderInvite create(UUID inviteId, UUID folderId, String token, FolderRole role, UUID createdBy, Instant now) {
        return new FolderInvite(inviteId, folderId, token, role, FolderInviteStatus.PENDING, createdBy, null, now, now, null);
    }

    public FolderInvite accept(UUID actorId, Instant now) {
        return new FolderInvite(inviteId, folderId, token, role, FolderInviteStatus.ACCEPTED, createdBy, actorId, createdAt, now, now);
    }
}
