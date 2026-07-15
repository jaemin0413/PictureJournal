package com.picturejournal.domain.share.dto.request;

import java.util.UUID;

/**
 * 외부 공유 API 입력 값을 전달하는 SelectFolderRequest 요청 DTO다.
 * 요청 시점의 입력 묶음을 변경 불가능한 값으로 전달한다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 */
public record SelectFolderRequest(UUID folderId) {
}
