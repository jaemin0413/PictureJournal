package com.picturejournal.domain.auth.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 인증 도메인의 상태와 식별자를 표현하는 UserAccount 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param userId 사용자 계정의 고유 식별자
 * @param email 정규화 및 중복 확인 대상 이메일 주소
 * @param displayName 화면에 표시할 사용자 이름
 * @param passwordHash salt와 반복 횟수가 포함된 비밀번호 해시
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record UserAccount(
        UUID userId,
        String email,
        String displayName,
        String passwordHash,
        Instant createdAt,
        Instant updatedAt) {

    public UserAccount {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
