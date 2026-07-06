package com.picturejournal.place.application;

import com.picturejournal.place.domain.PlaceCandidate;
import com.picturejournal.place.domain.SavedPlace;
import com.picturejournal.place.domain.ShareIntakeItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaceStore {

    ShareIntakeItem saveIntake(ShareIntakeItem intakeItem);

    Optional<ShareIntakeItem> findIntakeById(UUID intakeId);
    List<ShareIntakeItem> listIntakes();

    List<ShareIntakeItem> listIntakesByFolderId(UUID folderId);

    PlaceCandidate saveCandidate(PlaceCandidate candidate);

    List<PlaceCandidate> listCandidatesByIntakeId(UUID intakeId);

    SavedPlace savePlace(SavedPlace savedPlace);

    Optional<SavedPlace> findPlaceById(UUID placeId);

    List<SavedPlace> listPlacesByFolderId(UUID folderId);

    void deletePlace(UUID placeId);
}
