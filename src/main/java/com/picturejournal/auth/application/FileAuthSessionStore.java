package com.picturejournal.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
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
        try {
            Files.createDirectories(rootDirectory);
            objectMapper.writeValue(sessionPathRequired(authSession.token()).toFile(), authSession);
            return authSession;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist auth session " + authSession.token(), exception);
        }
    }

    @Override
    public synchronized Optional<AuthSession> findByToken(String token) {
        Optional<Path> sessionPath = sessionPath(token);
        if (sessionPath.isEmpty() || !Files.exists(sessionPath.get())) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(sessionPath.get().toFile(), AuthSession.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read auth session " + token, exception);
        }
    }

    private Path sessionPathRequired(String token) {
        return sessionPath(token).orElseThrow(() -> new IllegalArgumentException("token must use a safe file name."));
    }

    private Optional<Path> sessionPath(String token) {
        if (token == null || !SAFE_TOKEN_PATTERN.matcher(token).matches()) {
            return Optional.empty();
        }
        return Optional.of(rootDirectory.resolve(token + ".json"));
    }
}
