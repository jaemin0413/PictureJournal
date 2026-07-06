package com.picturejournal.collaboration.application;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.collaboration.domain.FolderInvite;
import com.picturejournal.folder.domain.FolderRole;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCollaborationStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void ignoresUnsafeInviteTokenPaths() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileCollaborationStore store = new FileCollaborationStore(objectMapper, tempDir.resolve("collaboration"));
        store.saveInvite(FolderInvite.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", ""),
                FolderRole.VIEWER,
                UUID.randomUUID(),
                Instant.parse("2026-07-06T00:00:00Z")));

        Optional<FolderInvite> invite = store.findInviteByToken("../escape");

        assertTrue(invite.isEmpty());
    }
}
