package com.picturejournal.domain.collaboration.dto.request;

import com.picturejournal.domain.collaboration.vo.FolderType;

/**
 * 협업 API 입력 값을 전달하는 CreateFolderRequest 요청 DTO다.
 * 요청 시점의 입력 묶음을 변경 불가능한 값으로 전달한다.
 *
 * @param type 폴더가 제공하는 기능 유형
 * @param name 사용자에게 표시할 이름
 * @param description 폴더를 설명하는 선택 입력
 */
public record CreateFolderRequest(FolderType type, String name, String description) {
}
