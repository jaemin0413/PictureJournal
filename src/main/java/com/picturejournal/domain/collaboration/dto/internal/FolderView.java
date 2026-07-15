package com.picturejournal.domain.collaboration.dto.internal;

import com.picturejournal.domain.collaboration.entity.Folder;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import com.picturejournal.domain.collaboration.vo.FolderType;
import java.time.Instant;
import java.util.UUID;

/**
 * 협업 계층 사이에서 값을 전달하는 FolderView 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param type 폴더가 제공하는 기능 유형
 * @param name 사용자에게 표시할 이름
 * @param description 폴더를 설명하는 선택 입력
 * @param role 폴더 멤버에게 부여된 권한 역할
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record FolderView(
        UUID folderId,
        FolderType type,
        String name,
        String description,
        FolderRole role,
        Instant createdAt,
        Instant updatedAt) {

    public static FolderView from(Folder folder, FolderRole role) {
        return new FolderView(folder.folderId(), folder.type(), folder.name(), folder.description(), role, folder.createdAt(), folder.updatedAt());
    }
}
