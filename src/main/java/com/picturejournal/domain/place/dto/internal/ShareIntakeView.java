package com.picturejournal.domain.place.dto.internal;

import com.picturejournal.domain.place.entity.PlaceCandidate;
import com.picturejournal.domain.place.entity.SavedPlace;
import com.picturejournal.domain.place.entity.ShareIntakeItem;
import java.util.List;

/**
 * 장소 계층 사이에서 값을 전달하는 ShareIntakeView 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param intake 현재 공유 수집 항목과 후보를 묶은 뷰
 * @param candidates 수집 원문에서 추출된 장소 후보 목록
 * @param resolvedPlace 이미 확정된 장소이며 미확정 상태에서는 null
 */
public record ShareIntakeView(ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace) {

    public static ShareIntakeView from(ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace) {
        return new ShareIntakeView(intake, List.copyOf(candidates), resolvedPlace);
    }
}
