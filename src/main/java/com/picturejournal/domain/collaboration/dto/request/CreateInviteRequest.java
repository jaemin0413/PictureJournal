package com.picturejournal.domain.collaboration.dto.request;

import com.picturejournal.domain.collaboration.vo.FolderRole;

/**
 * 협업 API 입력 값을 전달하는 CreateInviteRequest 요청 DTO다.
 * 요청 시점의 입력 묶음을 변경 불가능한 값으로 전달한다.
 *
 * @param role 폴더 멤버에게 부여된 권한 역할
 */
public record CreateInviteRequest(FolderRole role) {
}
