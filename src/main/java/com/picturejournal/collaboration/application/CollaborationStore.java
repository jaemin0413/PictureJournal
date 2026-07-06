package com.picturejournal.collaboration.application;

import com.picturejournal.collaboration.domain.Folder;
import com.picturejournal.collaboration.domain.FolderInvite;
import com.picturejournal.collaboration.domain.FolderMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollaborationStore {

    Folder saveFolder(Folder folder);

    Optional<Folder> findFolderById(UUID folderId);

    FolderMembership saveMembership(FolderMembership membership);

    Optional<FolderMembership> findMembership(UUID folderId, UUID actorId);

    List<FolderMembership> listMembershipsByActorId(UUID actorId);

    List<FolderMembership> listMembershipsByFolderId(UUID folderId);

    FolderInvite saveInvite(FolderInvite invite);

    Optional<FolderInvite> findInviteByToken(String token);
}
