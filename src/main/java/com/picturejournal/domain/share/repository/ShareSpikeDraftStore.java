package com.picturejournal.domain.share.repository;

import com.picturejournal.domain.share.entity.ShareSpikeDraft;
import java.util.Optional;
import java.util.UUID;

/**
 * 외부 공유 데이터 저장소의 계약을 정의하는 ShareSpikeDraftStore 타입이다.
 */
public interface ShareSpikeDraftStore {

    /**
     * 전달된 도메인 객체를 저장하고 저장 결과를 반환한다.
     *
     * @param draft 저장할 공유 초안
     * @return save 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    ShareSpikeDraft save(ShareSpikeDraft draft);

    /**
     * 고유 식별자로 저장된 객체를 조회한다.
     *
     * @param draftId 공유 초안 식별자
     * @return findById 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<ShareSpikeDraft> findById(UUID draftId);
}
