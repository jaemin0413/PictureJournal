package com.picturejournal.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.List;
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
        String persistedJson = Files.readString(tempDir.resolve("users").resolve(account.userId() + ".json"), StandardCharsets.UTF_8);

        assertFalse(persistedJson.contains("\"passwordHash\":\"secret\""));
        assertFalse(account.passwordHash().equals("secret"));
    }

    @Test
    void expiresOldSessions() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileUserAccountStore userStore = new FileUserAccountStore(objectMapper, tempDir.resolve("users"));
        FileAuthSessionStore sessionStore = new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions"));
        Instant start = Instant.parse("2026-07-06T00:00:00Z");
        AuthService authService = new AuthService(userStore, sessionStore, Clock.fixed(start, ZoneOffset.UTC));

        UserAccount account = authService.signup(new AuthService.SignupCommand("owner@example.com", "Owner", "secret"));
        AuthService.AuthenticatedSession login = authService.login(new AuthService.LoginCommand("owner@example.com", "secret"));

        AuthService expiredService = new AuthService(
                userStore,
                sessionStore,
                Clock.fixed(start.plusSeconds(12 * 60 * 60 + 1), ZoneOffset.UTC));

        DomainException exception = assertThrows(
                DomainException.class,
                () -> expiredService.getCurrentUser("Bearer " + login.authSession().token()));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        assertEquals(account.userId(), login.userAccount().userId());
    }

    @Test
    void malformedStoredPasswordHashFailsAsUnauthorized() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileUserAccountStore userStore = new FileUserAccountStore(objectMapper, tempDir.resolve("users"));
        FileAuthSessionStore sessionStore = new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions"));
        Instant start = Instant.parse("2026-07-06T00:00:00Z");
        AuthService authService = new AuthService(userStore, sessionStore, Clock.fixed(start, ZoneOffset.UTC));

        userStore.save(new UserAccount(
                java.util.UUID.randomUUID(),
                "owner@example.com",
                "Owner",
                "not:a:valid:hash",
                start,
                start));

        DomainException exception = assertThrows(
                DomainException.class,
                () -> authService.login(new AuthService.LoginCommand("owner@example.com", "secret")));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
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

    private Object awaitAndRun(CountDownLatch start, Callable<?> callable) throws Exception {
        start.await();
        return callable.call();
    }
}
