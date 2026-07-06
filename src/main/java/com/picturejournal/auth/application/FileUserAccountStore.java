package com.picturejournal.auth.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.auth.domain.UserAccount;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FileUserAccountStore implements UserAccountStore {

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;

    @Autowired
    public FileUserAccountStore(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("build", "auth", "users"));
    }

    public FileUserAccountStore(ObjectMapper objectMapper, Path rootDirectory) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory must not be null");
    }

    @Override
    public synchronized UserAccount save(UserAccount userAccount) {
        try {
            Files.createDirectories(rootDirectory);
            objectMapper.writeValue(userPath(userAccount.userId()).toFile(), userAccount);
            return userAccount;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist user account " + userAccount.userId(), exception);
        }
    }

    @Override
    public synchronized Optional<UserAccount> findById(UUID userId) {
        Path userPath = userPath(userId);
        if (!Files.exists(userPath)) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(userPath.toFile(), UserAccount.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read user account " + userId, exception);
        }
    }

    @Override
    public synchronized Optional<UserAccount> findByEmail(String email) {
        try {
            Files.createDirectories(rootDirectory);
            try (Stream<Path> paths = Files.list(rootDirectory)) {
                return paths
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .map(this::readUserAccount)
                        .filter(userAccount -> userAccount.email().equals(email))
                        .findFirst();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan user accounts for email " + email, exception);
        }
    }

    private UserAccount readUserAccount(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), UserAccount.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read user account file " + path, exception);
        }
    }

    private Path userPath(UUID userId) {
        return rootDirectory.resolve(userId + ".json");
    }
}
