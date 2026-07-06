package com.picturejournal.media.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.media.domain.MediaAsset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FileMediaAssetStore implements MediaAssetStore {

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;

    @Autowired
    public FileMediaAssetStore(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("build", "media"));
    }

    public FileMediaAssetStore(ObjectMapper objectMapper, Path rootDirectory) {
        this.objectMapper = objectMapper;
        this.rootDirectory = rootDirectory;
    }

    @Override
    public synchronized MediaAsset save(MediaAsset mediaAsset) {
        writeJson(assetPath(mediaAsset.mediaId()), mediaAsset, "media asset " + mediaAsset.mediaId());
        return mediaAsset;
    }

    @Override
    public synchronized Optional<MediaAsset> findById(UUID mediaId) {
        Path path = assetPath(mediaId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(readRequired(path, MediaAsset.class, "media asset " + mediaId));
    }

    public Path blobPath(String storageKey) {
        return rootDirectory.resolve("blobs").resolve(storageKey);
    }

    private Path assetPath(UUID mediaId) {
        return rootDirectory.resolve("assets").resolve(mediaId + ".json");
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
