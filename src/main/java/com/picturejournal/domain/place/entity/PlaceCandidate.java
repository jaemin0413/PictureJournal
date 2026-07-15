package com.picturejournal.domain.place.entity;

import java.util.Objects;
import java.util.UUID;

/**
 * 장소 도메인의 상태와 식별자를 표현하는 PlaceCandidate 엔티티다.
 * 도메인 상태의 한 시점을 변경 불가능한 값으로 표현한다.
 *
 * @param candidateId 사용자가 선택한 장소 후보의 식별자
 * @param intakeId 외부 공유 수집 항목의 고유 식별자
 * @param provider 장소 후보를 만든 지오코딩 공급자 또는 규칙 이름
 * @param name 사용자에게 표시할 이름
 * @param address 사용자에게 표시할 주소
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param confidence 후보 추출 결과의 신뢰도 점수
 * @param rawPayloadJson 후보 생성 근거를 보존한 원본 JSON
 */
public record PlaceCandidate(
        UUID candidateId,
        UUID intakeId,
        String provider,
        String name,
        String address,
        Double latitude,
        Double longitude,
        double confidence,
        String rawPayloadJson) {

    public PlaceCandidate {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(intakeId, "intakeId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
