package com.picturejournal.domain.auth.repository;

import com.picturejournal.domain.auth.entity.AuthSession;
import java.util.Optional;

/**
 * 인증 데이터 저장소의 계약을 정의하는 AuthSessionStore 타입이다.
 */
public interface AuthSessionStore {

    /**
     * 전달된 도메인 객체를 저장하고 저장 결과를 반환한다.
     *
     * @param authSession 저장할 인증 세션
     * @return save 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    AuthSession save(AuthSession authSession);

    /**
     * 토큰으로 인증 세션 또는 초대를 조회한다.
     *
     * @param token 초대 또는 인증 토큰
     * @return findByToken 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<AuthSession> findByToken(String token);
}
