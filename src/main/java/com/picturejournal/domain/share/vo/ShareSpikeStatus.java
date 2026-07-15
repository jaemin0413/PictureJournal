package com.picturejournal.domain.share.vo;

/**
 * 외부 공유 초안이 인증과 폴더 선택 과정 중 어디까지 진행됐는지 나타낸다.
 */
public enum ShareSpikeStatus {
    /** 사용자 인증 연결을 기다리는 초기 상태다. */
    PENDING_AUTH,
    /** 사용자는 연결됐지만 저장할 폴더가 아직 선택되지 않았다. */
    AWAITING_FOLDER_SELECTION,
    /** 사용자와 폴더가 모두 연결되어 후속 검토를 시작할 수 있다. */
    READY_FOR_REVIEW
}
