package com.picturejournal.folder.application;

import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit application-layer authorization boundary for folder-scoped writes.
 */
public interface FolderCapabilityPolicy {

    /**
     * Ensures that the actor may mutate state within the target folder scope.
     * Implementations may use any identity or permission source, but callers must
     * depend on this policy rather than bypassing authorization checks.
     */
    void assertCanWriteToFolder(UUID actorId, UUID folderId);

    static DomainException folderWriteNotAllowed(UUID actorId, UUID folderId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(folderId, "folderId must not be null");
        return new DomainException(
                ErrorCode.FOLDER_WRITE_NOT_ALLOWED,
                "Actor " + actorId + " cannot write to folder " + folderId);
    }
}
