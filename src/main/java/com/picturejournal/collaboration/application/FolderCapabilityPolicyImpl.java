package com.picturejournal.collaboration.application;

import com.picturejournal.folder.application.FolderCapabilityPolicy;
import com.picturejournal.folder.domain.FolderRole;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FolderCapabilityPolicyImpl implements FolderCapabilityPolicy {

    private final CollaborationStore collaborationStore;

    public FolderCapabilityPolicyImpl(CollaborationStore collaborationStore) {
        this.collaborationStore = collaborationStore;
    }

    @Override
    public void assertCanWriteToFolder(UUID actorId, UUID folderId) {
        collaborationStore.findMembership(folderId, actorId)
                .filter(membership -> membership.role() == FolderRole.OWNER || membership.role() == FolderRole.EDITOR)
                .orElseThrow(() -> FolderCapabilityPolicy.folderWriteNotAllowed(actorId, folderId));
    }
}
