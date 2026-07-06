package com.picturejournal.folder.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FolderCapabilityPolicyContractTests {

    @Test
    void folderWriteNotAllowedProducesExplicitFoundationErrorCode() {
        UUID actorId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID folderId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        DomainException exception = FolderCapabilityPolicy.folderWriteNotAllowed(actorId, folderId);

        assertSame(ErrorCode.FOLDER_WRITE_NOT_ALLOWED, exception.getErrorCode());
        assertEquals("Actor " + actorId + " cannot write to folder " + folderId, exception.getMessage());
    }

    @Test
    void callersCanRouteFolderScopedWritesThroughPolicyBoundary() {
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        RecordingFolderCapabilityPolicy allowedPolicy = new RecordingFolderCapabilityPolicy(false);
        RecordingFolderCapabilityPolicy deniedPolicy = new RecordingFolderCapabilityPolicy(true);

        FolderScopedWriteService allowedService = new FolderScopedWriteService(allowedPolicy);
        FolderScopedWriteService deniedService = new FolderScopedWriteService(deniedPolicy);

        assertDoesNotThrow(() -> allowedService.write(actorId, folderId));
        assertEquals(actorId, allowedPolicy.lastActorId);
        assertEquals(folderId, allowedPolicy.lastFolderId);

        DomainException denied = assertThrows(DomainException.class, () -> deniedService.write(actorId, folderId));
        assertSame(ErrorCode.FOLDER_WRITE_NOT_ALLOWED, denied.getErrorCode());
        assertEquals(actorId, deniedPolicy.lastActorId);
        assertEquals(folderId, deniedPolicy.lastFolderId);
    }

    private static final class FolderScopedWriteService {
        private final FolderCapabilityPolicy folderCapabilityPolicy;

        private FolderScopedWriteService(FolderCapabilityPolicy folderCapabilityPolicy) {
            this.folderCapabilityPolicy = folderCapabilityPolicy;
        }

        private void write(UUID actorId, UUID folderId) {
            folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        }
    }

    private static final class RecordingFolderCapabilityPolicy implements FolderCapabilityPolicy {
        private final boolean deny;
        private UUID lastActorId;
        private UUID lastFolderId;

        private RecordingFolderCapabilityPolicy(boolean deny) {
            this.deny = deny;
        }

        @Override
        public void assertCanWriteToFolder(UUID actorId, UUID folderId) {
            this.lastActorId = actorId;
            this.lastFolderId = folderId;
            if (deny) {
                throw FolderCapabilityPolicy.folderWriteNotAllowed(actorId, folderId);
            }
        }
    }
}
