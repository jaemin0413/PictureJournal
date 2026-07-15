package com.picturejournal.domain.share.service;

import com.picturejournal.domain.share.dto.internal.CreateShareSpikeDraftCommand;
import com.picturejournal.domain.share.entity.ShareSpikeDraft;
import com.picturejournal.domain.share.repository.ShareSpikeDraftStore;
import com.picturejournal.domain.share.vo.SharePayload;
import com.picturejournal.global.error.ErrorCode;
import com.picturejournal.global.exception.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 모바일 공유 시트에서 들어온 데이터를 인증 전후에 잃지 않도록 임시 보관한다.
 *
 * <p>초안 생성 시 원본 앱·플랫폼과 URL·제목·본문을 정규화한다. 콘텐츠가 하나도 없으면
 * 거부하며, 인증 사용자가 없는 상태에서 폴더만 지정하는 것도 허용하지 않는다.</p>
 *
 * <p>로그인 전 생성된 초안은 이후 {@link #bindActor(UUID, UUID)}로 사용자와 연결하고,
 * 인증 연결이 끝난 뒤에만 {@link #selectFolder(UUID, UUID)}로 대상 폴더를 선택할 수 있다.</p>
 */
@Service
public class ShareSpikeService {

    private final ShareSpikeDraftStore store;
    private final Clock clock;

    @Autowired
    public ShareSpikeService(ShareSpikeDraftStore store) {
        this(store, Clock.systemUTC());
    }

    ShareSpikeService(ShareSpikeDraftStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 외부 공유 원문을 검증하고 인증 전에도 유지할 수 있는 임시 초안을 생성한다.
     *
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 저장된 외부 공유 초안
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public ShareSpikeDraft createDraft(CreateShareSpikeDraftCommand command) {
        SharePayload payload = new SharePayload(
                normalizeRequired(command.sourceApp(), "sourceApp"),
                normalizeRequired(command.platform(), "platform"),
                normalizeOptional(command.rawUrl()),
                normalizeOptional(command.rawTitle()),
                normalizeOptional(command.rawText()));
        if (!payload.hasAnyContent()) {
            throw invalidArgument("At least one raw share payload field is required.");
        }
        if (command.actorId() == null && command.folderId() != null) {
            // 폴더 선택은 권한 검증의 주체가 필요하므로 익명 초안 단계에서는 허용하지 않는다.
            throw invalidArgument("folderId requires an authenticated actor.");
        }

        Instant now = Instant.now(clock);
        ShareSpikeDraft draft = ShareSpikeDraft.create(UUID.randomUUID(), command.actorId(), command.folderId(), payload, now);
        return store.save(draft);
    }

    /**
     * 공유 초안을 조회하고 없으면 리소스 없음 예외를 발생시킨다.
     *
     * @param draftId 대상 공유 초안 식별자
     * @return 식별자에 해당하는 외부 공유 초안
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public ShareSpikeDraft getDraft(UUID draftId) {
        return store.findById(draftId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Share spike draft " + draftId + " was not found."));
    }

    /**
     * 로그인 완료 후 공유 초안에 사용자 식별자를 연결한다.
     *
     * @param draftId 대상 공유 초안 식별자
     * @param actorId 요청을 수행하는 사용자 식별자
     * @return 사용자 연결 후 갱신된 공유 초안
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public ShareSpikeDraft bindActor(UUID draftId, UUID actorId) {
        if (actorId == null) {
            throw invalidArgument("actorId is required.");
        }
        ShareSpikeDraft draft = getDraft(draftId);
        return store.save(draft.bindActor(actorId, Instant.now(clock)));
    }

    /**
     * 인증 사용자가 연결된 공유 초안에 대상 폴더를 지정한다.
     *
     * @param draftId 대상 공유 초안 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @return 폴더 선택 후 갱신된 공유 초안
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public ShareSpikeDraft selectFolder(UUID draftId, UUID folderId) {
        if (folderId == null) {
            throw invalidArgument("folderId is required.");
        }
        ShareSpikeDraft draft = getDraft(draftId);
        if (draft.actorId() == null) {
            throw invalidArgument("folder selection requires an authenticated actor.");
        }
        return store.save(draft.selectFolder(folderId, Instant.now(clock)));
    }

    private DomainException invalidArgument(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
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
}
