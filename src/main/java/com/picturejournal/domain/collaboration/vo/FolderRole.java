package com.picturejournal.domain.collaboration.vo;

/**
 * 공유 폴더 멤버가 수행할 수 있는 작업 범위를 나타낸다.
 */
public enum FolderRole {
    /** 폴더 수정과 초대 생성을 포함한 모든 관리 작업을 수행할 수 있다. */
    OWNER,
    /** 폴더 콘텐츠를 생성·수정할 수 있지만 초대 생성은 할 수 없다. */
    EDITOR,
    /** 폴더와 콘텐츠를 조회할 수 있지만 변경할 수 없다. */
    VIEWER
}
