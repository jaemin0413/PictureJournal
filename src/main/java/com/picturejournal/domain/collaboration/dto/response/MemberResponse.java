package com.picturejournal.domain.collaboration.dto.response;

import com.picturejournal.domain.collaboration.dto.internal.MemberView;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import java.time.Instant;
import java.util.UUID;

/**
 * 협업 API 결과를 직렬화하는 MemberResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 * @param role 폴더 멤버에게 부여된 권한 역할
 * @param createdAt 레코드가 처음 생성된 시각
 */
public record MemberResponse(UUID folderId, UUID actorId, FolderRole role, Instant createdAt) {

    public static MemberResponse from(MemberView memberView) {
        return new MemberResponse(memberView.folderId(), memberView.actorId(), memberView.role(), memberView.createdAt());
    }
}
