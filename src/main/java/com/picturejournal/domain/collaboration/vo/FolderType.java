package com.picturejournal.domain.collaboration.vo;

/**
 * 공유 폴더가 담을 수 있는 콘텐츠와 적용할 비즈니스 규칙을 구분한다.
 */
public enum FolderType {
    /** 사진과 일기 콘텐츠를 저장하는 폴더다. */
    PHOTO_DIARY,
    /** 외부 공유에서 수집한 장소를 저장하는 폴더다. */
    REELS_PLACE
}
