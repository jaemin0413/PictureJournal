package com.picturejournal.auth.application;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
