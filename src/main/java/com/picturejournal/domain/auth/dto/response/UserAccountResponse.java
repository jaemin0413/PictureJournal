package com.picturejournal.domain.auth.dto.response;

import com.picturejournal.domain.auth.entity.UserAccount;
import java.time.Instant;
import java.util.UUID;

/**
 * 인증 API 결과를 직렬화하는 UserAccountResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param userId 사용자 계정의 고유 식별자
 * @param email 정규화 및 중복 확인 대상 이메일 주소
 * @param displayName 화면에 표시할 사용자 이름
 * @param createdAt 레코드가 처음 생성된 시각
 * @param updatedAt 레코드가 마지막으로 변경된 시각
 */
public record UserAccountResponse(
        UUID userId,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {

    public static UserAccountResponse from(UserAccount userAccount) {
        return new UserAccountResponse(
                userAccount.userId(),
                userAccount.email(),
                userAccount.displayName(),
                userAccount.createdAt(),
                userAccount.updatedAt());
    }
}
