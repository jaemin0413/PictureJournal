package com.picturejournal.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAuthSessionStoreTests {

    @TempDir
    Path tempDir;

    @Test
    void ignoresUnsafeSessionTokenPaths() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path sessions = tempDir.resolve("sessions");
        FileAuthSessionStore store = new FileAuthSessionStore(objectMapper, sessions);
        Path escaped = tempDir.resolve("escape.json");
        String sentinel = """
                {"userId":"00000000-0000-0000-0000-000000000001","createdAt":"2026-07-06T00:00:00Z"}
                """;
        Files.writeString(escaped, sentinel);

        assertTrue(store.findByToken("../escape").isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                store.save(new AuthSession("../escape", UUID.randomUUID(), Instant.parse("2026-07-06T00:00:00Z"))));
        assertThrows(IllegalArgumentException.class, () -> store.deleteByToken("../escape"));
        assertEquals(sentinel, Files.readString(escaped));
        assertFalse(Files.exists(sessions));
    }

    @Test
    void hashesBearerTokenInFileNameAndPersistsOnlySessionMetadata() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Path sessions = tempDir.resolve("sessions");
        FileAuthSessionStore store = new FileAuthSessionStore(objectMapper, sessions);
        String firstToken = "activeBearerToken_123";
        String secondToken = "anotherBearerToken_456";
        AuthSession first = new AuthSession(firstToken, UUID.randomUUID(), Instant.parse("2026-07-06T00:00:00Z"));
        AuthSession second = new AuthSession(secondToken, UUID.randomUUID(), Instant.parse("2026-07-06T00:01:00Z"));

        store.save(first);
        store.save(second);

        java.util.List<Path> storedPaths;
        try (var files = Files.list(sessions)) {
            storedPaths = files.sorted().toList();
        }
        assertEquals(2, storedPaths.size());
        assertNotEquals(storedPaths.get(0).getFileName(), storedPaths.get(1).getFileName());
        for (Path storedPath : storedPaths) {
            assertEquals(64 + ".json".length(), storedPath.getFileName().toString().length());
            String persisted = Files.readString(storedPath);
            assertFalse(persisted.contains(firstToken));
            assertFalse(persisted.contains(secondToken));
            java.util.Set<String> fields = new java.util.HashSet<>();
            objectMapper.readTree(persisted).fieldNames().forEachRemaining(fields::add);
            assertEquals(java.util.Set.of("userId", "createdAt"), fields);
        }
        assertEquals(first, store.findByToken(firstToken).orElseThrow());
        assertEquals(second, store.findByToken(secondToken).orElseThrow());

        store.deleteByToken(firstToken);
        assertTrue(store.findByToken(firstToken).isEmpty());
        assertEquals(second, store.findByToken(secondToken).orElseThrow());

        Path secondPath;
        try (var files = Files.list(sessions)) {
            secondPath = files.findFirst().orElseThrow();
        }
        Files.writeString(secondPath, "{");
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> store.findByToken(secondToken));
        for (Throwable current = exception; current != null; current = current.getCause()) {
            assertFalse(String.valueOf(current.getMessage()).contains(secondToken));
            assertFalse(current.toString().contains(secondToken));
        }
    }
}
