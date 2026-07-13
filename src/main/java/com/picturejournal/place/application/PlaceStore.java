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
    Optional<ShareIntakeItem> findIntakeByCreateReceipt(UUID actorId, UUID folderId, String clientIntakeId);
    Optional<AggregateRead> findAggregateByIntakeId(UUID intakeId);
    List<AggregateRead> listAggregatesByFolderId(UUID folderId);
    AggregateRead saveCreateReceipt(UUID intakeId, ShareIntakeItem.CreateReceipt receipt);
    List<ShareIntakeItem> listIntakes();

    List<ShareIntakeItem> listIntakesByFolderId(UUID folderId);

    PlaceCandidate saveCandidate(PlaceCandidate candidate);

    List<PlaceCandidate> listCandidatesByIntakeId(UUID intakeId);

    SavedPlace savePlace(SavedPlace savedPlace);
    AggregateWrite saveIntakeAggregate(ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace);
    ResolutionWrite saveResolution(ShareIntakeItem resolvedIntake, SavedPlace savedPlace);

    Optional<SavedPlace> findPlaceById(UUID placeId);

    List<SavedPlace> listPlacesByFolderId(UUID folderId);

    void deletePlace(UUID placeId);

    record AggregateWrite(ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace) {
    }
    record AggregateRead(ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace) {
    }

    record ResolutionWrite(ShareIntakeItem intake, SavedPlace place) {
    }
}
