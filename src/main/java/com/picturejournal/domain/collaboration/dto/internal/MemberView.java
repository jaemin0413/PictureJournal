package com.picturejournal.domain.collaboration.dto.internal;

import com.picturejournal.domain.collaboration.entity.FolderMembership;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import java.time.Instant;
import java.util.UUID;

/**
 * 협업 계층 사이에서 값을 전달하는 MemberView 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 * @param role 폴더 멤버에게 부여된 권한 역할
 * @param createdAt 레코드가 처음 생성된 시각
 */
public record MemberView(UUID folderId, UUID actorId, FolderRole role, Instant createdAt) {

    public static MemberView from(FolderMembership membership) {
        return new MemberView(membership.folderId(), membership.actorId(), membership.role(), membership.createdAt());
    }
}
