package com.picturejournal.domain.share.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.share.entity.ShareSpikeDraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 외부 공유 데이터를 파일 기반으로 저장하는 FileShareSpikeDraftStore 구현체다.
 */
@Component
public class FileShareSpikeDraftStore implements ShareSpikeDraftStore {

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;

    @Autowired
    public FileShareSpikeDraftStore(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("build", "share-spike-drafts"));
    }

    public FileShareSpikeDraftStore(ObjectMapper objectMapper, Path rootDirectory) {
        this.objectMapper = objectMapper;
        this.rootDirectory = rootDirectory;
    }

    @Override
    public synchronized ShareSpikeDraft save(ShareSpikeDraft draft) {
        try {
            Files.createDirectories(rootDirectory);
            objectMapper.writeValue(draftPath(draft.draftId()).toFile(), draft);
            return draft;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist share spike draft " + draft.draftId(), exception);
        }
    }

    @Override
    public synchronized Optional<ShareSpikeDraft> findById(UUID draftId) {
        Path draftPath = draftPath(draftId);
        if (!Files.exists(draftPath)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(draftPath.toFile(), ShareSpikeDraft.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read share spike draft " + draftId, exception);
        }
    }

    private Path draftPath(UUID draftId) {
        return rootDirectory.resolve(draftId + ".json");
    }
}
