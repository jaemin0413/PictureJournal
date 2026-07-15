package com.picturejournal.domain.collaboration.entity;

import com.picturejournal.domain.collaboration.vo.FolderType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 협업 도메인의 상태와 식별자를 표현하는 Folder 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param type 폴더가 제공하는 기능 유형
 * @param name 사용자에게 표시할 이름
 * @param description 폴더를 설명하는 선택 입력
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record Folder(
        UUID folderId,
        FolderType type,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public Folder {
        Objects.requireNonNull(folderId, "folderId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static Folder create(UUID folderId, FolderType type, String name, String description, Instant now) {
        return new Folder(folderId, type, name, description, now, now);
    }

    public Folder updateMetadata(String nextName, String nextDescription, Instant now) {
        return new Folder(folderId, type, nextName, nextDescription, createdAt, now);
    }
}
