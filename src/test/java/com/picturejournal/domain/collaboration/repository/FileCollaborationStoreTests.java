package com.picturejournal.domain.collaboration.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.collaboration.entity.FolderInvite;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
