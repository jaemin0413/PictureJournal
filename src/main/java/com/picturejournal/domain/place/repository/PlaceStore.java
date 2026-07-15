package com.picturejournal.domain.place.repository;

import com.picturejournal.domain.place.entity.PlaceCandidate;
import com.picturejournal.domain.place.entity.SavedPlace;
import com.picturejournal.domain.place.entity.ShareIntakeItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 장소 데이터 저장소의 계약을 정의하는 PlaceStore 타입이다.
 */
public interface PlaceStore {

    /**
     * 공유 수집 항목의 최신 상태를 저장한다.
     *
     * @param intakeItem 저장할 공유 수집 항목
     * @return saveIntake 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    ShareIntakeItem saveIntake(ShareIntakeItem intakeItem);

    /**
     * 식별자로 공유 수집 항목을 조회한다.
     *
     * @param intakeId 공유 수집 항목 식별자
     * @return findIntakeById 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<ShareIntakeItem> findIntakeById(UUID intakeId);
    /**
     * 전체 공유 수집 항목을 조회한다.
     *
     * @return listIntakes 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    List<ShareIntakeItem> listIntakes();

    /**
     * 특정 폴더에 연결된 공유 수집 항목을 조회한다.
     *
     * @param folderId 공유 폴더 식별자
     * @return listIntakesByFolderId 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    List<ShareIntakeItem> listIntakesByFolderId(UUID folderId);

    /**
     * 수집 항목에서 추출한 장소 후보를 저장한다.
     *
     * @param candidate 저장할 장소 후보
     * @return saveCandidate 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    PlaceCandidate saveCandidate(PlaceCandidate candidate);

    /**
     * 특정 수집 항목에서 추출된 장소 후보를 조회한다.
     *
     * @param intakeId 공유 수집 항목 식별자
     * @return listCandidatesByIntakeId 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    List<PlaceCandidate> listCandidatesByIntakeId(UUID intakeId);

    /**
     * 사용자가 확정한 장소를 저장한다.
     *
     * @param savedPlace 저장할 확정 장소
     * @return savePlace 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    SavedPlace savePlace(SavedPlace savedPlace);

    /**
     * 식별자로 확정 장소를 조회한다.
     *
     * @param placeId 저장 장소 식별자
     * @return findPlaceById 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<SavedPlace> findPlaceById(UUID placeId);

    /**
     * 특정 폴더에 저장된 장소를 조회한다.
     *
     * @param folderId 공유 폴더 식별자
     * @return listPlacesByFolderId 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    List<SavedPlace> listPlacesByFolderId(UUID folderId);

    /**
     * 식별자에 해당하는 저장 장소를 삭제한다.
     *
     * @param placeId 저장 장소 식별자
     */
    void deletePlace(UUID placeId);
}
