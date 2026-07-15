package com.picturejournal.domain.collaboration.service;

import com.picturejournal.domain.collaboration.dto.internal.CreateFolderCommand;
import com.picturejournal.domain.collaboration.dto.internal.CreateInviteCommand;
import com.picturejournal.domain.collaboration.dto.internal.FolderView;
import com.picturejournal.domain.collaboration.dto.internal.InviteView;
import com.picturejournal.domain.collaboration.dto.internal.MemberView;
import com.picturejournal.domain.collaboration.dto.internal.UpdateFolderCommand;
import com.picturejournal.domain.collaboration.entity.Folder;
import com.picturejournal.domain.collaboration.entity.FolderInvite;
import com.picturejournal.domain.collaboration.entity.FolderMembership;
import com.picturejournal.domain.collaboration.repository.CollaborationStore;
import com.picturejournal.domain.collaboration.service.FolderCapabilityPolicy;
import com.picturejournal.domain.collaboration.vo.FolderInviteStatus;
import com.picturejournal.domain.collaboration.vo.FolderRole;
import com.picturejournal.domain.collaboration.vo.FolderType;
import com.picturejournal.global.error.ErrorCode;
import com.picturejournal.global.exception.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 공유 폴더, 멤버십과 초대의 전체 생명주기를 조정한다.
 *
 * <p>폴더를 만들 때 생성자를 OWNER 멤버로 함께 저장하고, 폴더 수정과 초대 생성 전에는
 * {@link FolderCapabilityPolicy}로 쓰기 권한을 확인한다. 초대는 OWNER만 만들 수 있으며
 * EDITOR 또는 VIEWER 역할만 부여할 수 있다.</p>
 *
 * <p>초대 수락은 중복 멤버십과 초대 상태를 확인한 뒤 멤버십 저장과 초대 완료 처리를
 * 하나의 동기화 구간에서 수행한다. 실제 데이터 접근은 {@link CollaborationStore}에 위임한다.</p>
 */
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

    /**
     * 폴더와 소유자 멤버십을 함께 생성한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 생성된 폴더와 요청자의 역할 정보
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
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

    /**
     * 사용자가 참여 중인 폴더를 역할 정보와 함께 조회한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param type 조회할 폴더 기능 유형이며 null이면 전체 유형
     * @return 사용자가 참여 중인 폴더 목록
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
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

    /**
     * 사용자의 멤버십을 확인한 뒤 폴더를 조회한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @return 접근 가능한 폴더와 사용자 역할
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public FolderView getFolder(UUID actorId, UUID folderId) {
        Folder folder = requireAccessibleFolder(actorId, folderId);
        FolderMembership membership = requireMembership(folderId, actorId);
        return FolderView.from(folder, membership.role());
    }

    /**
     * 쓰기 권한을 확인하고 폴더 이름과 설명을 변경한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 변경 후 폴더 정보
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
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

    /**
     * 소유자 권한을 확인하고 편집자 또는 조회자 초대를 생성한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 생성된 대기 상태 초대
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
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

    /**
     * 아직 수락되지 않은 초대를 토큰으로 조회한다.
     *
     * @param token 조회하거나 수락할 인증·초대 토큰
     * @return 토큰에 해당하는 대기 상태 초대
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public InviteView getPendingInvite(String token) {
        FolderInvite invite = requireInvite(token);
        if (invite.status() != FolderInviteStatus.PENDING) {
            throw conflict("Invite " + token + " is no longer pending.");
        }
        return InviteView.from(invite);
    }

    /**
     * 초대 상태와 중복 멤버십을 확인한 뒤 새 멤버를 추가하고 초대를 완료 처리한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param token 조회하거나 수락할 인증·초대 토큰
     * @return 수락 완료 상태의 초대
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public synchronized InviteView acceptInvite(UUID actorId, String token) {
        // 같은 초대가 동시에 두 번 수락되어 중복 멤버십이 생기지 않도록 검사와 저장을 묶는다.
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
        // 멤버십을 먼저 저장한 뒤 초대를 완료 처리해 수락 결과의 역할과 시각을 동일하게 유지한다.
        collaborationStore.saveMembership(new FolderMembership(invite.folderId(), actorId, invite.role(), now));
        FolderInvite acceptedInvite = invite.accept(actorId, now);
        collaborationStore.saveInvite(acceptedInvite);
        return InviteView.from(acceptedInvite);
    }

    /**
     * 폴더 접근 권한을 확인한 뒤 전체 멤버와 역할을 반환한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @return 폴더의 멤버와 역할 목록
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
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
}
