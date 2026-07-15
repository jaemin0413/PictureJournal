package com.picturejournal.domain.collaboration.dto.internal;

/**
 * 협업 계층 사이에서 값을 전달하는 UpdateFolderCommand 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param name 사용자에게 표시할 이름
 * @param description 폴더를 설명하는 선택 입력
 */
public record UpdateFolderCommand(String name, String description) {
}
