package com.picturejournal.place.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.place.domain.PlaceCandidate;
import com.picturejournal.place.domain.SavedPlace;
import com.picturejournal.place.domain.ShareIntakeItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
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
        writeJson(intakePath(intakeItem.intakeId()), intakeItem, "share intake " + intakeItem.intakeId());
        return intakeItem;
    }

    @Override
    public synchronized Optional<ShareIntakeItem> findIntakeById(UUID intakeId) {
        return readOptional(intakePath(intakeId), ShareIntakeItem.class, "share intake " + intakeId);
    }
    @Override
    public synchronized List<ShareIntakeItem> listIntakes() {
        return listFiles(intakesDirectory(), ShareIntakeItem.class, "share intake")
                .sorted(Comparator.comparing(ShareIntakeItem::receivedAt).reversed())
                .toList();
    }

    @Override
    public synchronized List<ShareIntakeItem> listIntakesByFolderId(UUID folderId) {
        return listFiles(intakesDirectory(), ShareIntakeItem.class, "share intake")
                .filter(intake -> folderId.equals(intake.folderId()))
                .sorted(Comparator.comparing(ShareIntakeItem::receivedAt).reversed())
                .toList();
    }

    @Override
    public synchronized PlaceCandidate saveCandidate(PlaceCandidate candidate) {
        writeJson(candidatePath(candidate.candidateId()), candidate, "place candidate " + candidate.candidateId());
        return candidate;
    }

    @Override
    public synchronized List<PlaceCandidate> listCandidatesByIntakeId(UUID intakeId) {
        return listFiles(candidatesDirectory(), PlaceCandidate.class, "place candidate")
                .filter(candidate -> intakeId.equals(candidate.intakeId()))
                .sorted(Comparator.comparing(PlaceCandidate::confidence).reversed())
                .toList();
    }

    @Override
    public synchronized SavedPlace savePlace(SavedPlace savedPlace) {
        writeJson(placePath(savedPlace.placeId()), savedPlace, "saved place " + savedPlace.placeId());
        return savedPlace;
    }

    @Override
    public synchronized Optional<SavedPlace> findPlaceById(UUID placeId) {
        return readOptional(placePath(placeId), SavedPlace.class, "saved place " + placeId);
    }

    @Override
    public synchronized List<SavedPlace> listPlacesByFolderId(UUID folderId) {
        return listFiles(placesDirectory(), SavedPlace.class, "saved place")
                .filter(place -> folderId.equals(place.folderId()))
                .sorted(Comparator.comparing(SavedPlace::savedAt).reversed())
                .toList();
    }

    @Override
    public synchronized void deletePlace(UUID placeId) {
        try {
            Files.deleteIfExists(placePath(placeId));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete saved place " + placeId, exception);
        }
    }

    private <T> Stream<T> listFiles(Path directory, Class<T> type, String label) {
        if (!Files.exists(directory)) {
            return Stream.empty();
        }
        try {
            List<T> items;
            try (Stream<Path> stream = Files.list(directory)) {
                items = stream
                        .filter(Files::isRegularFile)
                        .map(path -> readRequired(path, type, label + " file " + path))
                        .toList();
            }
            return items.stream();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list " + label + " files", exception);
        }
    }

    private Path intakesDirectory() {
        return rootDirectory.resolve("intakes");
    }

    private Path candidatesDirectory() {
        return rootDirectory.resolve("candidates");
    }

    private Path placesDirectory() {
        return rootDirectory.resolve("places");
    }

    private Path intakePath(UUID intakeId) {
        return intakesDirectory().resolve(intakeId + ".json");
    }

    private Path candidatePath(UUID candidateId) {
        return candidatesDirectory().resolve(candidateId + ".json");
    }

    private Path placePath(UUID placeId) {
        return placesDirectory().resolve(placeId + ".json");
    }

    private <T> Optional<T> readOptional(Path path, Class<T> type, String label) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(readRequired(path, type, label));
    }

    private <T> T readRequired(Path path, Class<T> type, String label) {
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + label, exception);
        }
    }

    private void writeJson(Path path, Object value, String label) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist " + label, exception);
        }
    }
}
