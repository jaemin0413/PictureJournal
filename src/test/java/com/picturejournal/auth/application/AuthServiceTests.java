package com.picturejournal.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.auth.domain.UserAccount;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void storesPasswordAsHashInsteadOfPlaintext() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileUserAccountStore userStore = new FileUserAccountStore(objectMapper, tempDir.resolve("users"));
        FileAuthSessionStore sessionStore = new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions"));
        AuthService authService = new AuthService(
                userStore,
                sessionStore,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));

        UserAccount account = authService.signup(new AuthService.SignupCommand("owner@example.com", "Owner", "secret"));
        UserAccount secondAccount = authService.signup(new AuthService.SignupCommand("second@example.com", "Second", "secret"));
        String persistedJson = Files.readString(tempDir.resolve("users").resolve(account.userId() + ".json"), StandardCharsets.UTF_8);
        JsonNode persistedUser = objectMapper.readTree(persistedJson);
        String[] firstHash = account.passwordHash().split(":", -1);
        String[] secondHash = secondAccount.passwordHash().split(":", -1);

        assertFalse(persistedJson.contains("secret"));
        assertTrue(persistedUser.hasNonNull("passwordHash"));
        assertFalse(persistedUser.get("passwordHash").asText().contains("secret"));
        assertNoUnexpectedCredentialFields(persistedUser);
        assertEquals(3, firstHash.length);
        assertEquals("65536", firstHash[0]);
        assertEquals(16, Base64.getDecoder().decode(firstHash[1]).length);
        assertEquals(32, Base64.getDecoder().decode(firstHash[2]).length);
        assertEquals(3, secondHash.length);
        assertEquals("65536", secondHash[0]);
        assertEquals(16, Base64.getDecoder().decode(secondHash[1]).length);
        assertEquals(32, Base64.getDecoder().decode(secondHash[2]).length);
        assertFalse(firstHash[1].equals(secondHash[1]));
        assertFalse(firstHash[2].equals(secondHash[2]));
    }

    @Test
    void expiresSessionsAtExactTtlBoundary() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileUserAccountStore userStore = new FileUserAccountStore(objectMapper, tempDir.resolve("users"));
        FileAuthSessionStore sessionStore = new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions"));
        Instant start = Instant.parse("2026-07-06T00:00:00Z");
        AuthService authService = new AuthService(userStore, sessionStore, Clock.fixed(start, ZoneOffset.UTC));

        UserAccount account = authService.signup(new AuthService.SignupCommand("owner@example.com", "Owner", "secret"));
        AuthService.AuthenticatedSession login = authService.login(new AuthService.LoginCommand("owner@example.com", "secret"));

        AuthService activeService = new AuthService(
                userStore,
                sessionStore,
                Clock.fixed(start.plusSeconds(12 * 60 * 60).minusNanos(1), ZoneOffset.UTC));
        AuthService expiredService = new AuthService(
                userStore,
                sessionStore,
                Clock.fixed(start.plusSeconds(12 * 60 * 60), ZoneOffset.UTC));

        assertEquals(
                account.userId(),
                activeService.getCurrentUser("Bearer " + login.authSession().token()).userId());

        DomainException exception = assertThrows(
                DomainException.class,
                () -> expiredService.getCurrentUser("Bearer " + login.authSession().token()));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        assertEquals(account.userId(), login.userAccount().userId());
    }

    @Test
    void malformedStoredPasswordHashFailsAsUnauthorized() {
        String validSalt = Base64.getEncoder().encodeToString(new byte[16]);
        String validHash = Base64.getEncoder().encodeToString(new byte[32]);
        List<MalformedHashCase> malformedHashes = List.of(
                new MalformedHashCase("", null),
                new MalformedHashCase("65536:" + validSalt, null),
                new MalformedHashCase("65536:" + validSalt + ":" + validHash + ":extra", null),
                new MalformedHashCase("65535:" + validSalt + ":" + validHash, null),
                new MalformedHashCase("65536:" + Base64.getEncoder().encodeToString(new byte[15]) + ":" + validHash, null),
                new MalformedHashCase("65536:" + validSalt + ":" + Base64.getEncoder().encodeToString(new byte[31]), null),
                new MalformedHashCase("not-a-number:" + validSalt + ":" + validHash, NumberFormatException.class),
                new MalformedHashCase("65536:not-base64!:" + validHash, IllegalArgumentException.class));
        Instant start = Instant.parse("2026-07-06T00:00:00Z");

        for (int index = 0; index < malformedHashes.size(); index++) {
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            Path caseDirectory = tempDir.resolve("malformed-" + index);
            FileUserAccountStore userStore = new FileUserAccountStore(objectMapper, caseDirectory.resolve("users"));
            FileAuthSessionStore sessionStore = new FileAuthSessionStore(objectMapper, caseDirectory.resolve("sessions"));
            AuthService authService = new AuthService(userStore, sessionStore, Clock.fixed(start, ZoneOffset.UTC));
            MalformedHashCase malformedHash = malformedHashes.get(index);
            userStore.save(new UserAccount(
                    java.util.UUID.randomUUID(),
                    "owner@example.com",
                    "Owner",
                    malformedHash.passwordHash(),
                    start,
                    start));

            DomainException exception = assertThrows(
                    DomainException.class,
                    () -> authService.login(new AuthService.LoginCommand("owner@example.com", "secret")));

            assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
            assertEquals("Authentication is required.", exception.getMessage());
            Throwable malformedHashCause = assertInstanceOf(IllegalStateException.class, exception.getCause());
            if (malformedHash.nestedCauseType() == null) {
                assertNull(malformedHashCause.getCause());
            } else {
                assertInstanceOf(malformedHash.nestedCauseType(), malformedHashCause.getCause());
            }
        }
    }

    @Test
    void logoutDeletesTheSessionRecord() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileUserAccountStore userStore = new FileUserAccountStore(objectMapper, tempDir.resolve("users"));
        Path sessions = tempDir.resolve("sessions");
        FileAuthSessionStore sessionStore = new FileAuthSessionStore(objectMapper, sessions);
        Instant start = Instant.parse("2026-07-06T00:00:00Z");
        AuthService authService = new AuthService(userStore, sessionStore, Clock.fixed(start, ZoneOffset.UTC));

        authService.signup(new AuthService.SignupCommand("owner@example.com", "Owner", "secret"));
        AuthService.AuthenticatedSession login = authService.login(new AuthService.LoginCommand("owner@example.com", "secret"));
        Path digestFile;
        try (var files = Files.list(sessions)) {
            digestFile = files.findFirst().orElseThrow();
        }

        authService.logout("Bearer " + login.authSession().token());

        assertFalse(Files.exists(digestFile));
        assertTrue(sessionStore.findByToken(login.authSession().token()).isEmpty());
    }

    @Test
    void signupPreventsDuplicateEmailUnderConcurrentCalls() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileUserAccountStore userStore = new FileUserAccountStore(objectMapper, tempDir.resolve("users"));
        FileAuthSessionStore sessionStore = new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions"));
        AuthService authService = new AuthService(
                userStore,
                sessionStore,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Object>> tasks = List.of(
                    () -> awaitAndRun(start, () -> authService.signup(new AuthService.SignupCommand("owner@example.com", "Owner A", "secret"))),
                    () -> awaitAndRun(start, () -> authService.signup(new AuthService.SignupCommand("owner@example.com", "Owner B", "secret"))));
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();

            int successCount = 0;
            int conflictCount = 0;
            for (Future<Object> future : futures) {
                try {
                    future.get();
                    successCount++;
                } catch (Exception exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof DomainException domainException && domainException.getErrorCode() == ErrorCode.CONFLICT) {
                        conflictCount++;
                    } else {
                        throw exception;
                    }
                }
            }

            assertEquals(1, successCount);
            assertEquals(1, conflictCount);
        }
    }

    private void assertNoUnexpectedCredentialFields(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                String fieldName = field.getKey().toLowerCase(Locale.ROOT);
                if (fieldName.contains("password") || fieldName.contains("credential") || fieldName.contains("secret")
                        || fieldName.contains("token") || fieldName.contains("key")) {
                    assertEquals("passwordHash", field.getKey());
                }
                assertNoUnexpectedCredentialFields(field.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::assertNoUnexpectedCredentialFields);
        }
    }

    private record MalformedHashCase(String passwordHash, Class<? extends Throwable> nestedCauseType) {
    }
    private Object awaitAndRun(CountDownLatch start, Callable<?> callable) throws Exception {
        start.await();
        return callable.call();
    }
}
