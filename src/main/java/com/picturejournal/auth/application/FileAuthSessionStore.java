package com.picturejournal.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FileAuthSessionStore implements AuthSessionStore {

    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;

    @Autowired
    public FileAuthSessionStore(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("build", "auth", "sessions"));
    }

    public FileAuthSessionStore(ObjectMapper objectMapper, Path rootDirectory) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
    }

    @Override
    public synchronized AuthSession save(AuthSession authSession) {
        Path sessionPath = sessionPathRequired(authSession.token());
        try {
            Files.createDirectories(rootDirectory);
            objectMapper.writeValue(
                    sessionPath.toFile(),
                    new StoredAuthSession(authSession.userId(), authSession.createdAt()));
            return authSession;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist auth session.", exception);
        }
    }
    @Override
    public synchronized void deleteByToken(String token) {
        try {
            Files.deleteIfExists(sessionPathRequired(token));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete auth session.", exception);
        }
    }

    @Override
    public synchronized Optional<AuthSession> findByToken(String token) {
        Optional<Path> sessionPath = sessionPath(token);
        if (sessionPath.isEmpty() || !Files.exists(sessionPath.get())) {
            return Optional.empty();
        }

        try {
            StoredAuthSession stored = objectMapper.readValue(sessionPath.get().toFile(), StoredAuthSession.class);
            return Optional.of(new AuthSession(token, stored.userId(), stored.createdAt()));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read auth session.", exception);
        }
    }

    private Path sessionPathRequired(String token) {
        return sessionPath(token).orElseThrow(() -> new IllegalArgumentException("token must use a safe file name."));
    }

    private Optional<Path> sessionPath(String token) {
        if (token == null || !SAFE_TOKEN_PATTERN.matcher(token).matches()) {
            return Optional.empty();
        }
        return Optional.of(rootDirectory.resolve(tokenDigest(token) + ".json"));
    }

    private String tokenDigest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record StoredAuthSession(UUID userId, Instant createdAt) {
    }
}
