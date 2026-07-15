package com.picturejournal.domain.share.dto.request;

import java.util.UUID;

/**
 * 외부 공유 API 입력 값을 전달하는 BindAuthRequest 요청 DTO다.
 * 요청 시점의 입력 묶음을 변경 불가능한 값으로 전달한다.
 *
 * @param actorId 요청을 수행하거나 데이터에 연결된 사용자 식별자
 */
public record BindAuthRequest(UUID actorId) {
}
