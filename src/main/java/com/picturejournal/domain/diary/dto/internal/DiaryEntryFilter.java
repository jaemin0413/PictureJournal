package com.picturejournal.domain.diary.dto.internal;

import com.picturejournal.domain.diary.entity.DiaryEntry;
import java.time.Instant;
import java.util.Locale;

/**
 * 사진 일기 계층 사이에서 값을 전달하는 DiaryEntryFilter 내부 DTO다.
 * controller, service, repository 사이의 전달 값을 명시적으로 묶는다.
 *
 * @param tag 목록에 반드시 포함돼야 할 일기 태그
 * @param place 수집 항목에서 최종 확정된 저장 장소
 * @param from 조회 범위의 시작 시각
 * @param to 조회 범위의 종료 시각
 */
public record DiaryEntryFilter(String tag, String place, Instant from, Instant to) {

    public boolean matches(DiaryEntry entry) {
        return matchesTag(entry) && matchesPlace(entry) && matchesFrom(entry) && matchesTo(entry);
    }

    private boolean matchesTag(DiaryEntry entry) {
        if (tag == null || tag.isBlank()) {
            return true;
        }
        String expected = tag.trim().toLowerCase(Locale.ROOT);
        return entry.tags().stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(expected::equals);
    }

    private boolean matchesPlace(DiaryEntry entry) {
        if (place == null || place.isBlank()) {
            return true;
        }
        String expected = place.trim().toLowerCase(Locale.ROOT);
        return entry.placeName() != null && entry.placeName().toLowerCase(Locale.ROOT).contains(expected);
    }

    private boolean matchesFrom(DiaryEntry entry) {
        return from == null || entry.capturedAt() == null || !entry.capturedAt().isBefore(from);
    }

    private boolean matchesTo(DiaryEntry entry) {
        return to == null || entry.capturedAt() == null || !entry.capturedAt().isAfter(to);
    }
}
