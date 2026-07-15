package com.picturejournal.domain.place.dto.internal;

import com.picturejournal.domain.place.entity.SavedPlace;
import com.picturejournal.domain.place.vo.VisitStatus;
import java.util.Locale;

/**
 * 장소 계층 사이에서 값을 전달하는 SavedPlaceFilter 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param category 장소를 구분하는 사용자 지정 분류
 * @param status 현재 처리 단계 또는 생명주기 상태
 * @param keyword 이름·주소 등에 적용할 자유 검색어
 * @param region 목록을 제한할 지역 검색 조건
 */
public record SavedPlaceFilter(String category, VisitStatus status, String keyword, String region) {

    public boolean matches(SavedPlace place) {
        return matchesCategory(place) && matchesStatus(place) && matchesKeyword(place) && matchesRegion(place);
    }

    private boolean matchesCategory(SavedPlace place) {
        return category == null || category.isBlank() || (place.category() != null && place.category().equalsIgnoreCase(category.trim()));
    }

    private boolean matchesStatus(SavedPlace place) {
        return status == null || place.visitStatus() == status;
    }

    private boolean matchesKeyword(SavedPlace place) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String expected = keyword.trim().toLowerCase(Locale.ROOT);
        return place.keywords().stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(expected::equals);
    }

    private boolean matchesRegion(SavedPlace place) {
        return region == null || region.isBlank() || (place.regionText() != null && place.regionText().toLowerCase(Locale.ROOT).contains(region.trim().toLowerCase(Locale.ROOT)));
    }
}
