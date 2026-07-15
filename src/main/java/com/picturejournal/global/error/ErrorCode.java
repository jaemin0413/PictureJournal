package com.picturejournal.global.error;

/**
 * 도메인 실패를 HTTP 응답과 클라이언트 분기 처리에 연결하는 안정적인 오류 분류다.
 * 메시지는 상황에 따라 바뀔 수 있지만 이 값은 API 계약으로 유지한다.
 */
public enum ErrorCode {
    /** 필수값 누락, 형식 오류 또는 허용 범위를 벗어난 입력이다. */
    INVALID_ARGUMENT,
    /** 요청한 식별자에 해당하는 리소스가 없다. */
    RESOURCE_NOT_FOUND,
    /** 현재 리소스 상태와 요청 작업이 충돌한다. */
    CONFLICT,
    /** 사용자는 식별됐지만 요청 작업 권한이 없다. */
    FORBIDDEN,
    /** 유효한 인증 세션을 확인할 수 없다. */
    UNAUTHORIZED,
    /** 폴더 역할이 콘텐츠 변경을 허용하지 않는다. */
    FOLDER_WRITE_NOT_ALLOWED,
    /** 설정된 시간 구간의 호출 허용량을 초과했다. */
    RATE_LIMITED,
    /** 예상하지 못한 서버 내부 오류다. */
    INTERNAL_ERROR
}
