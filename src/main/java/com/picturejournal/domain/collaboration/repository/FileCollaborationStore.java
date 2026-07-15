package com.picturejournal.domain.collaboration.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.collaboration.entity.Folder;
import com.picturejournal.domain.collaboration.entity.FolderInvite;
import com.picturejournal.domain.collaboration.entity.FolderMembership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 협업 데이터를 파일 기반으로 저장하는 FileCollaborationStore 구현체다.
 */
@Component
public class FileCollaborationStore implements CollaborationStore {

    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final ObjectMapper objectMapper;
    private final Path rootDirectory;

    @Autowired
    public FileCollaborationStore(ObjectMapper objectMapper) {
        this(objectMapper, Paths.get("build", "collaboration"));
    }

    public FileCollaborationStore(ObjectMapper objectMapper, Path rootDirectory) {
        this.objectMapper = objectMapper;
        this.rootDirectory = rootDirectory;
    }

    @Override
    public synchronized Folder saveFolder(Folder folder) {
        writeJson(folderPath(folder.folderId()), folder, "folder " + folder.folderId());
        return folder;
    }

    @Override
    public synchronized Optional<Folder> findFolderById(UUID folderId) {
        return readOptional(folderPath(folderId), Folder.class, "folder " + folderId);
    }

    @Override
    public synchronized FolderMembership saveMembership(FolderMembership membership) {
        writeJson(membershipPath(membership.folderId(), membership.actorId()), membership,
                "membership " + membership.folderId() + "/" + membership.actorId());
        return membership;
    }

    @Override
    public synchronized Optional<FolderMembership> findMembership(UUID folderId, UUID actorId) {
        return readOptional(membershipPath(folderId, actorId), FolderMembership.class, "membership " + folderId + "/" + actorId);
    }

    @Override
    public synchronized List<FolderMembership> listMembershipsByActorId(UUID actorId) {
        Path membershipsDirectory = membershipsDirectory();
        if (!Files.exists(membershipsDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(membershipsDirectory, 2)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> readRequired(path, FolderMembership.class, "membership file " + path))
                    .filter(membership -> membership.actorId().equals(actorId))
                    .sorted(Comparator.comparing(FolderMembership::createdAt))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list memberships for actor " + actorId, exception);
        }
    }

    @Override
    public synchronized List<FolderMembership> listMembershipsByFolderId(UUID folderId) {
        Path folderMembershipDirectory = membershipsDirectory().resolve(folderId.toString());
        if (!Files.exists(folderMembershipDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folderMembershipDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> readRequired(path, FolderMembership.class, "membership file " + path))
                    .sorted(Comparator.comparing(FolderMembership::createdAt))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list memberships for folder " + folderId, exception);
        }
    }

    @Override
    public synchronized FolderInvite saveInvite(FolderInvite invite) {
        writeJson(invitePathRequired(invite.token()), invite, "invite " + invite.token());
        return invite;
    }

    @Override
    public synchronized Optional<FolderInvite> findInviteByToken(String token) {
        Optional<Path> invitePath = invitePath(token);
        if (invitePath.isEmpty()) {
            return Optional.empty();
        }
        return readOptional(invitePath.get(), FolderInvite.class, "invite " + token);
    }

    private Path folderPath(UUID folderId) {
        return rootDirectory.resolve("folders").resolve(folderId + ".json");
    }

    private Path membershipPath(UUID folderId, UUID actorId) {
        return membershipsDirectory().resolve(folderId.toString()).resolve(actorId + ".json");
    }

    private Path membershipsDirectory() {
        return rootDirectory.resolve("memberships");
    }

    private Path invitePathRequired(String token) {
        return invitePath(token).orElseThrow(() -> new IllegalArgumentException("token must use a safe file name."));
    }

    private Optional<Path> invitePath(String token) {
        if (token == null || !SAFE_TOKEN_PATTERN.matcher(token).matches()) {
            return Optional.empty();
        }
        return Optional.of(rootDirectory.resolve("invites").resolve(token + ".json"));
    }

    private <T> Optional<T> readOptional(Path path, Class<T> type, String label) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(readRequired(path, type, label));
    }

    private <T> T readRequired(Path path, Class<T> type, String label) {
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + label, exception);
        }
    }

    private void writeJson(Path path, Object value, String label) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist " + label, exception);
        }
    }
}
