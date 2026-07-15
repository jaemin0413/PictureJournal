package com.picturejournal.domain.diary.service;

import com.picturejournal.domain.collaboration.entity.Folder;
import com.picturejournal.domain.collaboration.repository.CollaborationStore;
import com.picturejournal.domain.collaboration.service.FolderCapabilityPolicy;
import com.picturejournal.domain.collaboration.vo.FolderType;
import com.picturejournal.domain.diary.dto.internal.CreateDiaryEntryCommand;
import com.picturejournal.domain.diary.dto.internal.DiaryEntryFilter;
import com.picturejournal.domain.diary.dto.internal.UpdateDiaryEntryCommand;
import com.picturejournal.domain.diary.entity.DiaryEntry;
import com.picturejournal.domain.diary.repository.DiaryEntryStore;
import com.picturejournal.domain.media.entity.MediaAsset;
import com.picturejournal.domain.media.service.MediaService;
import com.picturejournal.global.error.ErrorCode;
import com.picturejournal.global.exception.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 사진 일기의 생성, 검색, 수정과 삭제 유스케이스를 담당한다.
 *
 * <p>모든 작업에서 일기가 속한 폴더가 PHOTO_DIARY 유형인지 확인한다.
 * 조회에는 폴더 멤버십이 필요하고 변경 작업에는 추가로 쓰기 권한이 필요하다.
 * 미디어가 연결되는 경우 {@link MediaService}를 통해 존재 여부와 사용 가능 상태를 검증한다.</p>
 *
 * <p>입력 문자열과 태그를 정규화하고 위치 좌표가 쌍으로 제공됐는지 검사한 뒤
 * 변경 불가능한 {@link DiaryEntry}를 만들어 {@link DiaryEntryStore}에 저장한다.</p>
 */
@Service
public class DiaryService {

    private final DiaryEntryStore diaryEntryStore;
    private final CollaborationStore collaborationStore;
    private final FolderCapabilityPolicy folderCapabilityPolicy;
    private final MediaService mediaService;
    private final Clock clock;

    @Autowired
    public DiaryService(
            DiaryEntryStore diaryEntryStore,
            CollaborationStore collaborationStore,
            FolderCapabilityPolicy folderCapabilityPolicy,
            MediaService mediaService) {
        this(diaryEntryStore, collaborationStore, folderCapabilityPolicy, mediaService, Clock.systemUTC());
    }

    DiaryService(
            DiaryEntryStore diaryEntryStore,
            CollaborationStore collaborationStore,
            FolderCapabilityPolicy folderCapabilityPolicy,
            MediaService mediaService,
            Clock clock) {
        this.diaryEntryStore = diaryEntryStore;
        this.collaborationStore = collaborationStore;
        this.folderCapabilityPolicy = folderCapabilityPolicy;
        this.mediaService = mediaService;
        this.clock = clock;
    }

    /**
     * 폴더와 미디어를 검증한 뒤 새 사진 일기를 저장한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 저장된 사진 일기
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public DiaryEntry createEntry(UUID actorId, UUID folderId, CreateDiaryEntryCommand command) {
        requirePhotoDiaryMember(actorId, folderId);
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        MediaAsset mediaAsset = mediaService.requireMedia(requireUuid(command.mediaId(), "mediaId"));
        if (!mediaAsset.uploaderUserId().equals(actorId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the uploader can attach this media asset.");
        }
        ResolvedLocation location = resolveLocation(command.latitude(), command.longitude(), mediaAsset);
        Instant now = Instant.now(clock);
        DiaryEntry entry = DiaryEntry.create(
                UUID.randomUUID(),
                folderId,
                actorId,
                mediaAsset.mediaId(),
                normalizeRequired(command.title(), "title"),
                normalizeOptional(command.body()),
                normalizeOptional(command.placeName()),
                location.latitude(),
                location.longitude(),
                command.capturedAt() == null ? (mediaAsset.takenAt() == null ? now : mediaAsset.takenAt()) : command.capturedAt(),
                normalizeTags(command.tags()),
                now);
        return diaryEntryStore.save(entry);
    }

    /**
     * 폴더 접근 권한을 확인하고 조건에 맞는 사진 일기 목록을 반환한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @param filter 목록 결과에 적용할 선택 검색 조건
     * @return 필터와 정렬이 적용된 사진 일기 목록
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public List<DiaryEntry> listEntries(UUID actorId, UUID folderId, DiaryEntryFilter filter) {
        requirePhotoDiaryMember(actorId, folderId);
        return diaryEntryStore.listByFolderId(folderId).stream()
                .filter(entry -> filter == null || filter.matches(entry))
                .toList();
    }

    /**
     * 일기의 소속 폴더 접근 권한을 확인하고 단일 일기를 반환한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param entryId 대상 사진 일기 식별자
     * @return 접근 권한이 확인된 사진 일기
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public DiaryEntry getEntry(UUID actorId, UUID entryId) {
        DiaryEntry entry = requireEntry(entryId);
        requirePhotoDiaryMember(actorId, entry.folderId());
        return entry;
    }

    /**
     * 쓰기 권한과 미디어를 다시 검증한 뒤 일기 내용을 변경한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param entryId 대상 사진 일기 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 변경 후 사진 일기
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public DiaryEntry updateEntry(UUID actorId, UUID entryId, UpdateDiaryEntryCommand command) {
        DiaryEntry entry = requireEntry(entryId);
        requirePhotoDiaryMember(actorId, entry.folderId());
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, entry.folderId());
        ResolvedLocation location = resolveUpdatedLocation(command.latitude(), command.longitude(), entry);
        DiaryEntry updated = entry.update(
                command.title() == null ? entry.title() : normalizeRequired(command.title(), "title"),
                command.body() == null ? entry.body() : normalizeOptional(command.body()),
                command.placeName() == null ? entry.placeName() : normalizeOptional(command.placeName()),
                location.latitude(),
                location.longitude(),
                command.capturedAt() == null ? entry.capturedAt() : command.capturedAt(),
                command.tags() == null ? entry.tags() : normalizeTags(command.tags()),
                Instant.now(clock));
        return diaryEntryStore.save(updated);
    }

    /**
     * 쓰기 권한을 확인한 뒤 사진 일기를 삭제한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param entryId 대상 사진 일기 식별자
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public void deleteEntry(UUID actorId, UUID entryId) {
        DiaryEntry entry = requireEntry(entryId);
        requirePhotoDiaryMember(actorId, entry.folderId());
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, entry.folderId());
        diaryEntryStore.delete(entryId);
    }

    private Folder requirePhotoDiaryMember(UUID actorId, UUID folderId) {
        Folder folder = collaborationStore.findFolderById(folderId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Folder " + folderId + " was not found."));
        if (folder.type() != FolderType.PHOTO_DIARY) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Diary entries can only be used in PHOTO_DIARY folders.");
        }
        collaborationStore.findMembership(folderId, actorId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Actor " + actorId + " is not a member of folder " + folderId + "."));
        return folder;
    }

    private DiaryEntry requireEntry(UUID entryId) {
        return diaryEntryStore.findById(entryId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Diary entry " + entryId + " was not found."));
    }

    private UUID requireUuid(UUID value, String fieldName) {
        if (value == null) {
            throw invalidArgument(fieldName + " is required.");
        }
        return value;
    }

    private ResolvedLocation resolveLocation(Double latitude, Double longitude, MediaAsset mediaAsset) {
        Double resolvedLatitude = latitude == null ? mediaAsset.gpsLatitude() : latitude;
        Double resolvedLongitude = longitude == null ? mediaAsset.gpsLongitude() : longitude;
        return requireLocation(resolvedLatitude, resolvedLongitude);
    }

    private ResolvedLocation resolveUpdatedLocation(Double latitude, Double longitude, DiaryEntry entry) {
        double resolvedLatitude = latitude == null ? entry.latitude() : latitude;
        double resolvedLongitude = longitude == null ? entry.longitude() : longitude;
        return requireLocation(resolvedLatitude, resolvedLongitude);
    }

    private ResolvedLocation requireLocation(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw invalidArgument("latitude and longitude are required when EXIF GPS is unavailable.");
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw invalidArgument("latitude or longitude is out of range.");
        }
        return new ResolvedLocation(latitude, longitude);
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

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String tag : tags) {
            String trimmed = normalizeOptional(tag);
            if (trimmed != null) {
                normalized.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return List.copyOf(normalized.values());
    }

    private DomainException invalidArgument(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private record ResolvedLocation(double latitude, double longitude) {
    }
}
