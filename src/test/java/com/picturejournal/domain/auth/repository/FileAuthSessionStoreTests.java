package com.picturejournal.domain.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.auth.entity.AuthSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAuthSessionStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void ignoresUnsafeSessionTokenPaths() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileAuthSessionStore store = new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions"));
        store.save(new AuthSession(UUID.randomUUID().toString(), UUID.randomUUID(), Instant.parse("2026-07-06T00:00:00Z")));

        Optional<AuthSession> session = store.findByToken("../escape");

        assertTrue(session.isEmpty());
    }
}
