package com.picturejournal.domain.collaboration.dto.response;

import com.picturejournal.domain.collaboration.dto.internal.FolderView;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import com.picturejournal.domain.collaboration.vo.FolderType;
import java.time.Instant;
import java.util.UUID;

/**
 * 협업 API 결과를 직렬화하는 FolderResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param type 폴더가 제공하는 기능 유형
 * @param name 사용자에게 표시할 이름
 * @param description 폴더를 설명하는 선택 입력
 * @param role 폴더 멤버에게 부여된 권한 역할
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record FolderResponse(
        UUID folderId,
        FolderType type,
        String name,
        String description,
        FolderRole role,
        Instant createdAt,
        Instant updatedAt) {

    public static FolderResponse from(FolderView folderView) {
        return new FolderResponse(
                folderView.folderId(),
                folderView.type(),
                folderView.name(),
                folderView.description(),
                folderView.role(),
                folderView.createdAt(),
                folderView.updatedAt());
    }
}
