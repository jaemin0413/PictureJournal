package com.picturejournal.domain.collaboration.repository;

import com.picturejournal.domain.collaboration.entity.Folder;
import com.picturejournal.domain.collaboration.entity.FolderInvite;
import com.picturejournal.domain.collaboration.entity.FolderMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 협업 데이터 저장소의 계약을 정의하는 CollaborationStore 타입이다.
 */
public interface CollaborationStore {

    /**
     * 폴더 메타데이터를 저장한다.
     *
     * @param folder 저장할 폴더
     * @return saveFolder 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Folder saveFolder(Folder folder);

    /**
     * 식별자로 공유 폴더를 조회한다.
     *
     * @param folderId 공유 폴더 식별자
     * @return findFolderById 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<Folder> findFolderById(UUID folderId);

    /**
     * 폴더와 사용자 사이의 멤버십을 저장한다.
     *
     * @param membership 저장할 폴더 멤버십
     * @return saveMembership 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    FolderMembership saveMembership(FolderMembership membership);

    /**
     * 폴더와 사용자 조합으로 멤버십을 조회한다.
     *
     * @param folderId 공유 폴더 식별자
     * @param actorId 사용자 식별자
     * @return findMembership 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<FolderMembership> findMembership(UUID folderId, UUID actorId);

    /**
     * 사용자가 참여 중인 모든 폴더 멤버십을 조회한다.
     *
     * @param actorId 사용자 식별자
     * @return listMembershipsByActorId 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    List<FolderMembership> listMembershipsByActorId(UUID actorId);

    /**
     * 폴더에 속한 모든 멤버십을 조회한다.
     *
     * @param folderId 공유 폴더 식별자
     * @return listMembershipsByFolderId 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    List<FolderMembership> listMembershipsByFolderId(UUID folderId);

    /**
     * 폴더 초대의 최신 상태를 저장한다.
     *
     * @param invite 저장할 폴더 초대
     * @return saveInvite 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    FolderInvite saveInvite(FolderInvite invite);

    /**
     * 초대 토큰으로 폴더 초대를 조회한다.
     *
     * @param token 초대 또는 인증 토큰
     * @return findInviteByToken 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<FolderInvite> findInviteByToken(String token);
}
