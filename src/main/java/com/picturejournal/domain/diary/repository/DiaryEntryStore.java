package com.picturejournal.domain.diary.repository;

import com.picturejournal.domain.diary.entity.DiaryEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 사진 일기 데이터 저장소의 계약을 정의하는 DiaryEntryStore 타입이다.
 */
public interface DiaryEntryStore {

    /**
     * 전달된 도메인 객체를 저장하고 저장 결과를 반환한다.
     *
     * @param diaryEntry 조회 또는 저장 조건으로 사용할 diaryEntry 값
     * @return save 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    DiaryEntry save(DiaryEntry diaryEntry);

    /**
     * 고유 식별자로 저장된 객체를 조회한다.
     *
     * @param entryId 사진 일기 식별자
     * @return findById 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<DiaryEntry> findById(UUID entryId);

    /**
     * 특정 폴더에 저장된 사진 일기를 조회한다.
     *
     * @param folderId 공유 폴더 식별자
     * @return listByFolderId 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    List<DiaryEntry> listByFolderId(UUID folderId);

    /**
     * delete 저장소 작업을 수행한다.
     *
     * @param entryId 사진 일기 식별자
     */
    void delete(UUID entryId);
}
