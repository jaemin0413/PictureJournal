package com.picturejournal.domain.place.dto.internal;

import com.picturejournal.domain.place.entity.SavedPlace;

/**
 * 장소 계층 사이에서 값을 전달하는 ResolveShareIntakeResult 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param intake 현재 공유 수집 항목과 후보를 묶은 뷰
 * @param savedPlace 이번 확정 과정에서 생성된 저장 장소
 */
public record ResolveShareIntakeResult(ShareIntakeView intake, SavedPlace savedPlace) {
}
