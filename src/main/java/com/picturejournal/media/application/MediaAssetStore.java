package com.picturejournal.media.application;

import com.picturejournal.media.domain.MediaAsset;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetStore {

    MediaAsset saveWithBlob(MediaAsset mediaAsset, byte[] blobBytes);
    MediaAsset commitPending(UUID mediaId, UUID uploaderUserId, UUID folderId, UUID diaryEntryId, Instant committedAt);

    int cleanupExpiredPending(Instant now);

    Optional<MediaAsset> findById(UUID mediaId);

    Optional<Metadata> findMetadata(UUID mediaId);

    Snapshot readSnapshot(Metadata authorizedMetadata);

    record Metadata(MediaAsset mediaAsset, UUID generation) {
        public Metadata {
            if (mediaAsset == null || generation == null) {
                throw new IllegalArgumentException("mediaAsset and generation must not be null");
            }
        }
    }

    record Snapshot(MediaAsset mediaAsset, UUID generation, byte[] bytes) {
        public Snapshot {
            if (mediaAsset == null || generation == null || bytes == null) {
                throw new IllegalArgumentException("mediaAsset, generation, and bytes must not be null");
            }
        }
    }

    final class AssetNotFoundException extends RuntimeException {
        public AssetNotFoundException(UUID mediaId) {
            super("Media asset " + mediaId + " was not found.");
        }
    }
    final class PendingCommitException extends RuntimeException {
        public PendingCommitException(String message) {
            super(message);
        }
    }

    final class SnapshotChangedException extends RuntimeException {
        public SnapshotChangedException(UUID mediaId) {
            super("Media asset " + mediaId + " changed while it was being read.");
        }
    }

}
