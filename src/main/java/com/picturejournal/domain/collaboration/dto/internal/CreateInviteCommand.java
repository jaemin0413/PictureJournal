package com.picturejournal.domain.collaboration.dto.internal;

import com.picturejournal.domain.collaboration.vo.FolderRole;

/**
 * 협업 계층 사이에서 값을 전달하는 CreateInviteCommand 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param role 폴더 멤버에게 부여된 권한 역할
 */
public record CreateInviteCommand(FolderRole role) {
}
