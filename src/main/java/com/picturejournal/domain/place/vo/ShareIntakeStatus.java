package com.picturejournal.domain.place.vo;

/**
 * 공유 원문이 실제 저장 장소로 확정되기까지의 처리 단계를 나타낸다.
 */
public enum ShareIntakeStatus {
    /** 후보가 하나 추출되어 사용자의 확인만 필요하다. */
    NEEDS_CONFIRMATION,
    /** 후보가 여러 개라 사용자가 하나를 선택해야 한다. */
    NEEDS_SELECTION,
    /** 후보를 추출하지 못해 장소 정보를 직접 입력해야 한다. */
    NEEDS_MANUAL_FIX,
    /** 사용자가 나중에 처리할 수 있도록 임시 저장했다. */
    DRAFT,
    /** 저장 장소가 생성되어 처리가 완료됐다. */
    RESOLVED
}
