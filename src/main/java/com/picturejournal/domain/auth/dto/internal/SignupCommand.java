package com.picturejournal.domain.auth.dto.internal;

/**
 * 인증 계층 사이에서 값을 전달하는 SignupCommand 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param email 정규화 및 중복 확인 대상 이메일 주소
 * @param displayName 화면에 표시할 사용자 이름
 * @param password 검증 또는 해시에 사용할 원문 비밀번호
 */
public record SignupCommand(String email, String displayName, String password) {
}
