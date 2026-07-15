package com.picturejournal.domain.media.repository;

import com.picturejournal.domain.media.entity.MediaAsset;
import java.util.Optional;
import java.util.UUID;

/**
 * 미디어 데이터 저장소의 계약을 정의하는 MediaAssetStore 타입이다.
 */
public interface MediaAssetStore {

    /**
     * 전달된 도메인 객체를 저장하고 저장 결과를 반환한다.
     *
     * @param mediaAsset 저장할 미디어 메타데이터
     * @return save 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    MediaAsset save(MediaAsset mediaAsset);

    /**
     * 고유 식별자로 저장된 객체를 조회한다.
     *
     * @param mediaId 미디어 식별자
     * @return findById 작업 결과. 조회 결과가 없을 수 있으면 Optional로 표현한다.
     */
    Optional<MediaAsset> findById(UUID mediaId);
}
