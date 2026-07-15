package com.picturejournal.domain.collaboration.entity;

import com.picturejournal.domain.collaboration.vo.FolderRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 협업 도메인의 상태와 식별자를 표현하는 FolderMembership 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 * @param role 폴더 멤버에게 부여된 권한 역할
 * @param createdAt 레코드가 처음 생성된 시각
 */
public record FolderMembership(
        UUID folderId,
        UUID actorId,
        FolderRole role,
        Instant createdAt) {

    public FolderMembership {
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static FolderMembership owner(UUID folderId, UUID actorId, Instant now) {
        return new FolderMembership(folderId, actorId, FolderRole.OWNER, now);
    }
}
