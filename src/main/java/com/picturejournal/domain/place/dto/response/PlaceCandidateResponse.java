package com.picturejournal.domain.place.dto.response;

import com.picturejournal.domain.place.entity.PlaceCandidate;
import java.util.UUID;

/**
 * 장소 API 결과를 직렬화하는 PlaceCandidateResponse 응답 DTO다.
 * 외부 API에 노출되는 응답 모양을 고정한다.
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
public record PlaceCandidateResponse(
        UUID candidateId,
        UUID intakeId,
        String provider,
        String name,
        String address,
        Double latitude,
        Double longitude,
        double confidence,
        String rawPayloadJson) {

    public static PlaceCandidateResponse from(PlaceCandidate candidate) {
        return new PlaceCandidateResponse(candidate.candidateId(), candidate.intakeId(), candidate.provider(), candidate.name(),
                candidate.address(), candidate.latitude(), candidate.longitude(), candidate.confidence(), candidate.rawPayloadJson());
    }
}
