package com.picturejournal.domain.collaboration.vo;

/**
 * 폴더 초대가 사용 가능한지 또는 이미 소비됐는지 나타낸다.
 */
public enum FolderInviteStatus {
    /** 아직 수락되지 않아 초대 토큰을 사용할 수 있다. */
    PENDING,
    /** 특정 사용자가 수락하여 더 이상 다시 사용할 수 없다. */
    ACCEPTED
}
