package com.picturejournal.domain.place.dto.response;

import com.picturejournal.domain.place.dto.internal.ResolveShareIntakeResult;

/**
 * 장소 API 결과를 직렬화하는 ResolveShareIntakeResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
 *
 * @param intake 현재 공유 수집 항목과 후보를 묶은 뷰
 * @param savedPlace 이번 확정 과정에서 생성된 저장 장소
 */
public record ResolveShareIntakeResponse(ShareIntakeResponse intake, SavedPlaceResponse savedPlace) {

    public static ResolveShareIntakeResponse from(ResolveShareIntakeResult result) {
        return new ResolveShareIntakeResponse(ShareIntakeResponse.from(result.intake()), SavedPlaceResponse.from(result.savedPlace()));
    }
}
