package com.picturejournal.domain.auth.repository;

import com.picturejournal.domain.auth.entity.UserAccount;
import java.util.Optional;
import java.util.UUID;

/**
 * 인증 데이터 저장소의 계약을 정의하는 UserAccountStore 타입이다.
 */
public interface UserAccountStore {

    /**
     * 전달된 도메인 객체를 저장하고 저장 결과를 반환한다.
     *
     * @param userAccount 저장할 사용자 계정
     * @return save 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    UserAccount save(UserAccount userAccount);

    /**
     * 고유 식별자로 저장된 객체를 조회한다.
     *
     * @param userId 사용자 식별자
     * @return findById 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<UserAccount> findById(UUID userId);

    /**
     * 정규화된 이메일로 사용자 계정을 조회한다.
     *
     * @param email 정규화된 이메일
     * @return findByEmail 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<UserAccount> findByEmail(String email);
}
