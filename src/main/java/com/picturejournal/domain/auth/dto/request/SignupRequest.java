package com.picturejournal.domain.auth.dto.request;

/**
 * 인증 API 입력 값을 전달하는 SignupRequest 요청 DTO다.
 * 요청 시점의 입력 묶음을 변경 불가능한 값으로 전달한다.
 *
 * @param email 정규화 및 중복 확인 대상 이메일 주소
 * @param displayName 화면에 표시할 사용자 이름
 * @param password 검증 또는 해시에 사용할 원문 비밀번호
 */
public record SignupRequest(String email, String displayName, String password) {
}
