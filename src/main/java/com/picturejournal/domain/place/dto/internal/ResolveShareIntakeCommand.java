package com.picturejournal.domain.place.dto.internal;

import com.picturejournal.domain.place.vo.VisitStatus;
import java.util.List;
import java.util.UUID;

/**
 * 장소 계층 사이에서 값을 전달하는 ResolveShareIntakeCommand 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param folderId 데이터가 소속되거나 연결될 공유 폴더 식별자
 * @param candidateId 사용자가 선택한 장소 후보의 식별자
 * @param manualName 후보 대신 사용자가 직접 입력한 장소 이름
 * @param category 장소를 구분하는 사용자 지정 분류
 * @param address 사용자에게 표시할 주소
 * @param regionText 검색과 분류에 사용할 지역 문자열
 * @param latitude 위치의 위도 값
 * @param longitude 위치의 경도 값
 * @param summary 장소에 대한 간략한 설명
 * @param whyRecommended 장소를 저장하거나 추천한 이유
 * @param keywords 검색과 필터링에 사용할 중복 제거 키워드 목록
 * @param visitStatus 장소 방문 계획 또는 완료 상태
 */
public record ResolveShareIntakeCommand(
        UUID folderId,
        UUID candidateId,
        String manualName,
        String category,
        String address,
        String regionText,
        Double latitude,
        Double longitude,
        String summary,
        String whyRecommended,
        List<String> keywords,
        VisitStatus visitStatus) {
}
