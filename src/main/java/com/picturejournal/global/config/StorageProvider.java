package com.picturejournal.global.config;

/**
 * 미디어 바이너리를 어느 저장소 구현에 기록할지 선택한다.
 */
public enum StorageProvider {
    /** 애플리케이션이 실행되는 서버의 로컬 파일 시스템을 사용한다. */
    LOCAL,
    /** AWS S3 API와 호환되는 외부 객체 저장소를 사용한다. */
    S3_COMPATIBLE
}
