package com.picturejournal.place.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.place.domain.PlaceCandidate;
import com.picturejournal.place.domain.SavedPlace;
import com.picturejournal.place.domain.ShareIntakeItem;
import com.picturejournal.place.domain.ShareIntakeStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FilePlaceStore implements PlaceStore {

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;

    @Autowired
    public FilePlaceStore(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("build", "places"));
    }

    public FilePlaceStore(ObjectMapper objectMapper, Path rootDirectory) {
        this.objectMapper = objectMapper;
        this.rootDirectory = rootDirectory;
    }

    @Override
    public synchronized ShareIntakeItem saveIntake(ShareIntakeItem intakeItem) {
        throw new UnsupportedOperationException("Share intakes must be persisted through saveIntakeAggregate.");
    }

    @Override
    public synchronized Optional<ShareIntakeItem> findIntakeById(UUID intakeId) {
        return readState().intakes().stream().filter(intake -> intakeId.equals(intake.intakeId())).findFirst();
    }
    @Override
    public synchronized Optional<ShareIntakeItem> findIntakeByCreateReceipt(UUID actorId, UUID folderId, String clientIntakeId) {
        return readState().intakes().stream()
                .filter(intake -> folderId.equals(intake.folderId()))
                .filter(intake -> (actorId.equals(intake.receivedByUserId()) && clientIntakeId.equals(intake.clientIntakeId()))
                        || intake.createReceipts().stream().anyMatch(receipt -> actorId.equals(receipt.actorId())
                                && folderId.equals(receipt.folderId()) && clientIntakeId.equals(receipt.clientIntakeId())))
                .findFirst();
    }

    @Override
    public synchronized Optional<AggregateRead> findAggregateByIntakeId(UUID intakeId) {
        StoreState state = readState();
        return state.intakes().stream()
                .filter(intake -> intakeId.equals(intake.intakeId()))
                .findFirst()
                .map(intake -> aggregateFrom(state, intake));
    }

    @Override
    public synchronized List<AggregateRead> listAggregatesByFolderId(UUID folderId) {
        StoreState state = readState();
        return state.intakes().stream()
                .filter(intake -> folderId.equals(intake.folderId()))
                .sorted(Comparator.comparing(ShareIntakeItem::receivedAt).reversed())
                .map(intake -> aggregateFrom(state, intake))
                .toList();
    }

    @Override
    public synchronized AggregateRead saveCreateReceipt(UUID intakeId, ShareIntakeItem.CreateReceipt receipt) {
        StoreState state = readState();
        ShareIntakeItem intake = state.intakes().stream()
                .filter(item -> intakeId.equals(item.intakeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Share intake does not exist."));
        ShareIntakeItem updatedIntake = intake.withCreateReceipt(receipt);
        if (updatedIntake != intake) {
            state = state.withIntake(updatedIntake);
            writeState(state);
        }
        return aggregateFrom(state, updatedIntake);
    }

    @Override
    public synchronized List<ShareIntakeItem> listIntakes() {
        return readState().intakes().stream()
                .sorted(Comparator.comparing(ShareIntakeItem::receivedAt).reversed())
                .toList();
    }

    @Override
    public synchronized List<ShareIntakeItem> listIntakesByFolderId(UUID folderId) {
        return readState().intakes().stream()
                .filter(intake -> folderId.equals(intake.folderId()))
                .sorted(Comparator.comparing(ShareIntakeItem::receivedAt).reversed())
                .toList();
    }

    @Override
    public synchronized PlaceCandidate saveCandidate(PlaceCandidate candidate) {
        throw new UnsupportedOperationException("Place candidates must be persisted through saveIntakeAggregate.");
    }

    @Override
    public synchronized List<PlaceCandidate> listCandidatesByIntakeId(UUID intakeId) {
        return readState().candidates().stream()
                .filter(candidate -> intakeId.equals(candidate.intakeId()))
                .sorted(Comparator.comparing(PlaceCandidate::confidence).reversed())
                .toList();
    }

    @Override
    public synchronized SavedPlace savePlace(SavedPlace savedPlace) {
        StoreState state = readState();
        SavedPlace current = state.places().stream()
                .filter(place -> place.placeId().equals(savedPlace.placeId()))
                .findFirst()
                .orElse(null);
        if (current == null && savedPlace.shareIntakeId() != null) {
            throw new IllegalStateException("A place linked to a share intake must be persisted through saveResolution.");
        }
        if (current != null && (!current.folderId().equals(savedPlace.folderId())
                || !current.creatorUserId().equals(savedPlace.creatorUserId())
                || !java.util.Objects.equals(current.shareIntakeId(), savedPlace.shareIntakeId())
                || !current.savedAt().equals(savedPlace.savedAt())
                || !java.util.Objects.equals(current.resolvedAt(), savedPlace.resolvedAt()))) {
            throw new IllegalStateException("Saved place linkage and immutable fields cannot be changed.");
        }
        if (savedPlace.shareIntakeId() != null) {
            ShareIntakeItem intake = state.intakes().stream()
                    .filter(item -> item.intakeId().equals(savedPlace.shareIntakeId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Linked share intake does not exist."));
            if (intake.status() != ShareIntakeStatus.RESOLVED || !savedPlace.placeId().equals(intake.resolvedPlaceId())
                    || !savedPlace.folderId().equals(intake.folderId())) {
                throw new IllegalStateException("Linked saved place must match its resolved intake and folder.");
            }
        }
        writeState(state.withPlace(savedPlace));
        return savedPlace;
    }

    @Override
    public synchronized AggregateWrite saveIntakeAggregate(
            ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        if (candidates.stream().anyMatch(candidate -> !intake.intakeId().equals(candidate.intakeId()))) {
            throw new IllegalStateException("Every candidate must belong to the aggregate intake.");
        }
        if (candidates.stream().map(PlaceCandidate::candidateId).distinct().count() != candidates.size()) {
            throw new IllegalStateException("Candidate IDs must be unique within an intake aggregate.");
        }
        StoreState state = readState();
        ShareIntakeItem current = state.intakes().stream()
                .filter(item -> item.intakeId().equals(intake.intakeId()))
                .findFirst()
                .orElse(null);
        if (current != null) {
            if (current.status() == ShareIntakeStatus.RESOLVED) {
                throw new IllegalStateException("A resolved share intake cannot be downgraded or replaced.");
            }
            if (!current.folderId().equals(intake.folderId())
                    || !current.receivedByUserId().equals(intake.receivedByUserId())
                    || !current.clientIntakeId().equals(intake.clientIntakeId())
                    || !current.contentFingerprint().equals(intake.contentFingerprint())
                    || !current.createReceipts().equals(intake.createReceipts())
                    || !current.createIdentity().equals(intake.createIdentity())) {
                throw new IllegalStateException("Share intake immutable fields cannot be changed.");
            }
        }
        if (resolvedPlace == null && intake.status() == ShareIntakeStatus.RESOLVED) {
            throw new IllegalStateException("A resolved intake aggregate requires its linked place.");
        }
        if (resolvedPlace != null) {
            validateResolvedLinkage(intake, resolvedPlace);
            if (state.places().stream().anyMatch(place -> place.placeId().equals(resolvedPlace.placeId()))) {
                throw new IllegalStateException("Resolved place already exists.");
            }
        } else if (intake.status() == ShareIntakeStatus.RESOLVED) {
            throw new IllegalStateException("Resolved intake aggregate requires a saved place.");
        }
        StoreState updated = state.withIntake(intake).replaceCandidatesForIntake(intake.intakeId(), candidates);
        if (resolvedPlace != null) {
            updated = updated.withPlace(resolvedPlace);
        }
        writeState(updated);
        return new AggregateWrite(intake, candidates, resolvedPlace);
    }

    @Override
    public synchronized ResolutionWrite saveResolution(ShareIntakeItem resolvedIntake, SavedPlace savedPlace) {
        validateResolvedLinkage(resolvedIntake, savedPlace);
        StoreState state = readState();
        ShareIntakeItem currentIntake = state.intakes().stream()
                .filter(intake -> intake.intakeId().equals(resolvedIntake.intakeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Share intake must exist before resolution."));
        if (currentIntake.status() == ShareIntakeStatus.RESOLVED) {
            if (!currentIntake.resolvedPlaceId().equals(savedPlace.placeId())
                    || !currentIntake.resolutionIdentity().equals(resolvedIntake.resolutionIdentity())) {
                throw new IllegalStateException("A resolved share intake cannot be rebound or resolved differently.");
            }
            SavedPlace currentPlace = state.places().stream()
                    .filter(place -> place.placeId().equals(currentIntake.resolvedPlaceId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Resolved share intake references a missing place."));
            return new ResolutionWrite(currentIntake, currentPlace);
        }
        if (resolvedIntake.status() != ShareIntakeStatus.RESOLVED
                || !currentIntake.folderId().equals(resolvedIntake.folderId())
                || !currentIntake.contentFingerprint().equals(resolvedIntake.contentFingerprint())
                || !currentIntake.createIdentity().equals(resolvedIntake.createIdentity())) {
            throw new IllegalStateException("Resolution cannot alter intake identity or terminal state.");
        }
        if (state.places().stream().anyMatch(place -> place.placeId().equals(savedPlace.placeId()))) {
            throw new IllegalStateException("Resolved place already exists.");
        }
        writeState(state.withPlace(savedPlace).withIntake(resolvedIntake));
        return new ResolutionWrite(resolvedIntake, savedPlace);
    }

    @Override
    public synchronized Optional<SavedPlace> findPlaceById(UUID placeId) {
        return readState().places().stream().filter(place -> placeId.equals(place.placeId())).findFirst();
    }

    @Override
    public synchronized List<SavedPlace> listPlacesByFolderId(UUID folderId) {
        return readState().places().stream()
                .filter(place -> folderId.equals(place.folderId()))
                .sorted(Comparator.comparing(SavedPlace::savedAt).reversed())
                .toList();
    }

    @Override
    public synchronized void deletePlace(UUID placeId) {
        StoreState state = readState();
        if (state.intakes().stream().anyMatch(intake -> placeId.equals(intake.resolvedPlaceId()))) {
            throw new IllegalStateException("A place linked to a retained share intake cannot be deleted.");
        }
        writeState(state.withoutPlace(placeId));
    }
    private AggregateRead aggregateFrom(StoreState state, ShareIntakeItem intake) {
        List<PlaceCandidate> candidates = state.candidates().stream()
                .filter(candidate -> intake.intakeId().equals(candidate.intakeId()))
                .sorted(Comparator.comparing(PlaceCandidate::confidence).reversed())
                .toList();
        SavedPlace resolvedPlace = intake.resolvedPlaceId() == null ? null : state.places().stream()
                .filter(place -> intake.resolvedPlaceId().equals(place.placeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Resolved share intake references a missing place."));
        return new AggregateRead(intake, candidates, resolvedPlace);
    }

    private void validateResolvedLinkage(ShareIntakeItem intake, SavedPlace place) {
        if (intake.status() != ShareIntakeStatus.RESOLVED
                || !intake.intakeId().equals(place.shareIntakeId())
                || !intake.resolvedPlaceId().equals(place.placeId())
                || !intake.folderId().equals(place.folderId())) {
            throw new IllegalStateException("Resolved intake and saved place linkage must match.");
        }
    }
    private Path statePath() {
        return rootDirectory.resolve("store-state.json");
    }

    private StoreState readState() {
        Path path = statePath();
        if (!Files.exists(path)) {
            return StoreState.empty();
        }
        try {
            return objectMapper.readValue(path.toFile(), StoreState.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read place store state", exception);
        }
    }

    protected void writeState(StoreState state) {
        writeJson(statePath(), state, "place store state");
    }

    /**
     * Persistence requires atomic replacement. If a write or atomic move fails, a temp-file cleanup
     * failure is retained as suppressed evidence rather than replacing the primary failure.
     */
    private void writeJson(Path path, Object value, String label) {
        Path tempPath = null;
        RuntimeException primaryFailure = null;
        try {
            Files.createDirectories(path.getParent());
            tempPath = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            objectMapper.writeValue(tempPath.toFile(), value);
            Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            primaryFailure = new IllegalStateException("Failed to persist " + label, exception);
            throw primaryFailure;
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw new IllegalStateException("Failed to clean up temporary " + label + " file", cleanupFailure);
                    }
                }
            }
        }
    }

    public record StoreState(List<ShareIntakeItem> intakes, List<PlaceCandidate> candidates, List<SavedPlace> places) {
        public StoreState {
            intakes = List.copyOf(intakes);
            candidates = List.copyOf(candidates);
            places = List.copyOf(places);
        }

        static StoreState empty() {
            return new StoreState(List.of(), List.of(), List.of());
        }

        StoreState withIntake(ShareIntakeItem intake) {
            return new StoreState(replace(intakes, intake, ShareIntakeItem::intakeId), candidates, places);
        }

        StoreState replaceCandidatesForIntake(UUID intakeId, List<PlaceCandidate> replacement) {
            List<PlaceCandidate> updated = new ArrayList<>(candidates.size() + replacement.size());
            for (PlaceCandidate candidate : candidates) {
                if (!intakeId.equals(candidate.intakeId())) {
                    updated.add(candidate);
                }
            }
            updated.addAll(replacement);
            return new StoreState(intakes, updated, places);
        }

        StoreState withPlace(SavedPlace place) {
            return new StoreState(intakes, candidates, replace(places, place, SavedPlace::placeId));
        }

        StoreState withoutPlace(UUID placeId) {
            return new StoreState(intakes, candidates,
                    places.stream().filter(place -> !placeId.equals(place.placeId())).toList());
        }

        private static <T> List<T> replace(List<T> items, T replacement, java.util.function.Function<T, UUID> id) {
            List<T> updated = new ArrayList<>(items.size() + 1);
            boolean replaced = false;
            for (T item : items) {
                if (id.apply(item).equals(id.apply(replacement))) {
                    updated.add(replacement);
                    replaced = true;
                } else {
                    updated.add(item);
                }
            }
            if (!replaced) {
                updated.add(replacement);
            }
            return updated;
        }
    }
}
