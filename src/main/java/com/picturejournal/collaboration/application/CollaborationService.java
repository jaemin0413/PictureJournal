package com.picturejournal.collaboration.application;

import com.picturejournal.collaboration.domain.Folder;
import com.picturejournal.collaboration.domain.FolderInvite;
import com.picturejournal.collaboration.domain.FolderInviteStatus;
import com.picturejournal.collaboration.domain.FolderMembership;
import com.picturejournal.folder.application.FolderCapabilityPolicy;
import com.picturejournal.folder.domain.FolderRole;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CollaborationService {

    private final CollaborationStore collaborationStore;
    private final FolderCapabilityPolicy folderCapabilityPolicy;
    private final Clock clock;

    @Autowired
    public CollaborationService(CollaborationStore collaborationStore, FolderCapabilityPolicy folderCapabilityPolicy) {
        this(collaborationStore, folderCapabilityPolicy, Clock.systemUTC());
    }

    CollaborationService(CollaborationStore collaborationStore, FolderCapabilityPolicy folderCapabilityPolicy, Clock clock) {
        this.collaborationStore = collaborationStore;
        this.folderCapabilityPolicy = folderCapabilityPolicy;
        this.clock = clock;
    }

    public FolderView createFolder(UUID actorId, CreateFolderCommand command) {
        Instant now = Instant.now(clock);
        Folder folder = Folder.create(
                UUID.randomUUID(),
                requireFolderType(command.type()),
                normalizeRequired(command.name(), "name"),
                normalizeOptional(command.description()),
                now);
        FolderMembership membership = FolderMembership.owner(folder.folderId(), actorId, now);
        collaborationStore.saveFolder(folder);
        collaborationStore.saveMembership(membership);
        return FolderView.from(folder, membership.role());
    }

    public List<FolderView> listFolders(UUID actorId, FolderType type) {
        return collaborationStore.listMembershipsByActorId(actorId).stream()
                .map(membership -> {
                    Folder folder = collaborationStore.findFolderById(membership.folderId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Membership " + membership.folderId() + "/" + membership.actorId() + " references a missing folder."));
                    return FolderView.from(folder, membership.role());
                })
                .filter(folder -> type == null || folder.type() == type)
                .toList();
    }

    public FolderView getFolder(UUID actorId, UUID folderId) {
        Folder folder = requireAccessibleFolder(actorId, folderId);
        FolderMembership membership = requireMembership(folderId, actorId);
        return FolderView.from(folder, membership.role());
    }

    public FolderView updateFolder(UUID actorId, UUID folderId, UpdateFolderCommand command) {
        Folder folder = requireFolder(folderId);
        FolderMembership membership = requireMembership(folderId, actorId);
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        Folder updated = folder.updateMetadata(
                resolveUpdatedName(folder, command.name()),
                resolveUpdatedDescription(folder, command.description()),
                Instant.now(clock));
        collaborationStore.saveFolder(updated);
        return FolderView.from(updated, membership.role());
    }

    public InviteView createInvite(UUID actorId, UUID folderId, CreateInviteCommand command) {
        requireFolder(folderId);
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        FolderMembership membership = requireMembership(folderId, actorId);
        if (membership.role() != FolderRole.OWNER) {
            throw forbidden("Only owners can create folder invites.");
        }
        FolderRole role = requireInvitableRole(command.role());
        FolderInvite invite = FolderInvite.create(
                UUID.randomUUID(),
                folderId,
                UUID.randomUUID().toString().replace("-", ""),
                role,
                actorId,
                Instant.now(clock));
        collaborationStore.saveInvite(invite);
        return InviteView.from(invite);
    }

    public InviteView getPendingInvite(String token) {
        FolderInvite invite = requireInvite(token);
        if (invite.status() != FolderInviteStatus.PENDING) {
            throw conflict("Invite " + token + " is no longer pending.");
        }
        return InviteView.from(invite);
    }

    public synchronized InviteView acceptInvite(UUID actorId, String token) {
        FolderInvite invite = requireInvite(token);
        if (invite.status() != FolderInviteStatus.PENDING) {
            throw conflict("Invite " + token + " is no longer pending.");
        }
        requireFolder(invite.folderId());
        folderCapabilityPolicy.assertCanWriteToFolder(invite.createdBy(), invite.folderId());
        if (collaborationStore.findMembership(invite.folderId(), actorId).isPresent()) {
            throw conflict("Actor " + actorId + " is already a member of folder " + invite.folderId() + ".");
        }
        Instant now = Instant.now(clock);
        collaborationStore.saveMembership(new FolderMembership(invite.folderId(), actorId, invite.role(), now));
        FolderInvite acceptedInvite = invite.accept(actorId, now);
        collaborationStore.saveInvite(acceptedInvite);
        return InviteView.from(acceptedInvite);
    }

    public List<MemberView> listMembers(UUID actorId, UUID folderId) {
        requireAccessibleFolder(actorId, folderId);
        return collaborationStore.listMembershipsByFolderId(folderId).stream()
                .map(MemberView::from)
                .toList();
    }

    private Folder requireAccessibleFolder(UUID actorId, UUID folderId) {
        Folder folder = requireFolder(folderId);
        requireMembership(folderId, actorId);
        return folder;
    }

    private Folder requireFolder(UUID folderId) {
        return collaborationStore.findFolderById(folderId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Folder " + folderId + " was not found."));
    }

    private FolderInvite requireInvite(String token) {
        return collaborationStore.findInviteByToken(normalizeRequired(token, "token"))
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Invite " + token + " was not found."));
    }

    private FolderMembership requireMembership(UUID folderId, UUID actorId) {
        return collaborationStore.findMembership(folderId, actorId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Actor " + actorId + " is not a member of folder " + folderId + "."));
    }

    private FolderRole requireInvitableRole(FolderRole role) {
        if (role == FolderRole.EDITOR || role == FolderRole.VIEWER) {
            return role;
        }
        throw invalidArgument("Invites may only grant EDITOR or VIEWER role.");
    }

    private FolderType requireFolderType(FolderType type) {
        if (type == null) {
            throw invalidArgument("type is required.");
        }
        return type;
    }

    private DomainException invalidArgument(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private DomainException forbidden(String message) {
        return new DomainException(ErrorCode.FORBIDDEN, message);
    }

    private DomainException conflict(String message) {
        return new DomainException(ErrorCode.CONFLICT, message);
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw invalidArgument(fieldName + " is required.");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveUpdatedName(Folder folder, String requestedName) {
        if (requestedName == null) {
            return folder.name();
        }
        return normalizeRequired(requestedName, "name");
    }

    private String resolveUpdatedDescription(Folder folder, String requestedDescription) {
        if (requestedDescription == null) {
            return folder.description();
        }
        String trimmed = requestedDescription.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CreateFolderCommand(FolderType type, String name, String description) {
    }

    public record UpdateFolderCommand(String name, String description) {
    }

    public record CreateInviteCommand(FolderRole role) {
    }

    public record FolderView(
            UUID folderId,
            FolderType type,
            String name,
            String description,
            FolderRole role,
            Instant createdAt,
            Instant updatedAt) {

        static FolderView from(Folder folder, FolderRole role) {
            return new FolderView(folder.folderId(), folder.type(), folder.name(), folder.description(), role, folder.createdAt(), folder.updatedAt());
        }
    }

    public record InviteView(
            UUID inviteId,
            UUID folderId,
            String token,
            FolderRole role,
            FolderInviteStatus status,
            UUID createdBy,
            UUID acceptedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant acceptedAt) {

        static InviteView from(FolderInvite invite) {
            return new InviteView(
                    invite.inviteId(),
                    invite.folderId(),
                    invite.token(),
                    invite.role(),
                    invite.status(),
                    invite.createdBy(),
                    invite.acceptedBy(),
                    invite.createdAt(),
                    invite.updatedAt(),
                    invite.acceptedAt());
        }
    }

    public record MemberView(UUID folderId, UUID actorId, FolderRole role, Instant createdAt) {

        static MemberView from(FolderMembership membership) {
            return new MemberView(membership.folderId(), membership.actorId(), membership.role(), membership.createdAt());
        }
    }
}
