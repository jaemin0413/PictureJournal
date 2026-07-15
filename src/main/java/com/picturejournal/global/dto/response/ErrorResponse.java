package com.picturejournal.global.dto.response;

import java.time.Instant;
import java.util.Map;

/**
 * 공통 API 응답에 사용하는 ErrorResponse DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param timestamp 응답이 만들어진 서버 시각
 * @param status 현재 처리 단계 또는 생명주기 상태
 * @param error HTTP 오류 분류 문자열
 * @param code 클라이언트가 분기 처리할 안정적인 오류 코드
 * @param message 클라이언트 또는 로그에 제공할 설명 메시지
 * @param details 오류 원인을 구조화한 추가 정보
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        Map<String, Object> details) {
}
