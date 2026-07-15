package com.picturejournal.domain.auth.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 인증 도메인의 상태와 식별자를 표현하는 AuthSession 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param token 인증 또는 초대 확인에 사용하는 토큰
 * @param userId 사용자 계정의 고유 식별자
 * @param createdAt 레코드가 처음 생성된 시각
 */
public record AuthSession(
        String token,
        UUID userId,
        Instant createdAt) {

    public AuthSession {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
