package com.picturejournal.domain.collaboration.service;

import com.picturejournal.domain.collaboration.repository.CollaborationStore;
import com.picturejournal.domain.collaboration.service.FolderCapabilityPolicy;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 협업 도메인의 유스케이스와 비즈니스 규칙을 수행하는 FolderCapabilityPolicyImpl 타입이다.
 */
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
