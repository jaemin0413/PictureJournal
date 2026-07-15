package com.picturejournal.domain.auth.dto.response;

import com.picturejournal.domain.auth.dto.internal.AuthenticatedSession;

/**
 * 인증 API 결과를 직렬화하는 LoginResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param token 인증 또는 초대 확인에 사용하는 토큰
 * @param user API 응답으로 노출할 로그인 사용자 정보
 */
public record LoginResponse(String token, UserAccountResponse user) {

    public static LoginResponse from(AuthenticatedSession authenticatedSession) {
        return new LoginResponse(
                authenticatedSession.authSession().token(),
                UserAccountResponse.from(authenticatedSession.userAccount()));
    }
}
