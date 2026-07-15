package com.picturejournal.domain.auth.dto.internal;

import com.picturejournal.domain.auth.entity.AuthSession;
import com.picturejournal.domain.auth.entity.UserAccount;

/**
 * 인증 계층 사이에서 값을 전달하는 AuthenticatedSession 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param authSession 로그인으로 발급된 인증 세션
 * @param userAccount 인증 세션과 연결된 전체 사용자 계정
 */
public record AuthenticatedSession(AuthSession authSession, UserAccount userAccount) {
}
