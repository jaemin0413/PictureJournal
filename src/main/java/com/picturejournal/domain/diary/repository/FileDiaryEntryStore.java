package com.picturejournal.domain.diary.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.diary.entity.DiaryEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 사진 일기 데이터를 파일 기반으로 저장하는 FileDiaryEntryStore 구현체다.
 */
@Component
public class FileDiaryEntryStore implements DiaryEntryStore {

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;

    @Autowired
    public FileDiaryEntryStore(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("build", "diary"));
    }

    public FileDiaryEntryStore(ObjectMapper objectMapper, Path rootDirectory) {
        this.objectMapper = objectMapper;
        this.rootDirectory = rootDirectory;
    }

    @Override
    public synchronized DiaryEntry save(DiaryEntry diaryEntry) {
        writeJson(entryPath(diaryEntry.entryId()), diaryEntry, "diary entry " + diaryEntry.entryId());
        return diaryEntry;
    }

    @Override
    public synchronized Optional<DiaryEntry> findById(UUID entryId) {
        Path path = entryPath(entryId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(readRequired(path, DiaryEntry.class, "diary entry " + entryId));
    }

    @Override
    public synchronized List<DiaryEntry> listByFolderId(UUID folderId) {
        Path entriesDirectory = entriesDirectory();
        if (!Files.exists(entriesDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(entriesDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> readRequired(path, DiaryEntry.class, "diary entry file " + path))
                    .filter(entry -> entry.folderId().equals(folderId))
                    .sorted(Comparator.comparing(DiaryEntry::capturedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(DiaryEntry::createdAt, Comparator.reverseOrder()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list diary entries for folder " + folderId, exception);
        }
    }

    @Override
    public synchronized void delete(UUID entryId) {
        try {
            Files.deleteIfExists(entryPath(entryId));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete diary entry " + entryId, exception);
        }
    }

    private Path entriesDirectory() {
        return rootDirectory.resolve("entries");
    }

    private Path entryPath(UUID entryId) {
        return entriesDirectory().resolve(entryId + ".json");
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
