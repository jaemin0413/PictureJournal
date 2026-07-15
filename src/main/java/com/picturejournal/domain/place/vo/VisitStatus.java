package com.picturejournal.domain.place.vo;

/**
 * 저장한 장소에 대한 사용자의 방문 의사와 경험을 표현한다.
 */
public enum VisitStatus {
    /** 앞으로 방문하고 싶은 장소다. */
    WANT_TO_GO,
    /** 이미 방문한 장소다. */
    VISITED,
    /** 판단을 미루거나 일시적으로 보류한 장소다. */
    ON_HOLD,
    /** 다시 방문하거나 추천하고 싶지 않은 장소다. */
    NOT_GOOD
}
