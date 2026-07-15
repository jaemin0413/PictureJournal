package com.picturejournal.domain.place.service;

import com.picturejournal.domain.collaboration.entity.Folder;
import com.picturejournal.domain.collaboration.repository.CollaborationStore;
import com.picturejournal.domain.collaboration.service.FolderCapabilityPolicy;
import com.picturejournal.domain.collaboration.vo.FolderType;
import com.picturejournal.domain.place.dto.internal.CreateShareIntakeCommand;
import com.picturejournal.domain.place.dto.internal.ResolveShareIntakeCommand;
import com.picturejournal.domain.place.dto.internal.ResolveShareIntakeResult;
import com.picturejournal.domain.place.dto.internal.SavedPlaceFilter;
import com.picturejournal.domain.place.dto.internal.ShareIntakeView;
import com.picturejournal.domain.place.dto.internal.UpdateSavedPlaceCommand;
import com.picturejournal.domain.place.entity.PlaceCandidate;
import com.picturejournal.domain.place.entity.SavedPlace;
import com.picturejournal.domain.place.entity.ShareIntakeItem;
import com.picturejournal.domain.place.repository.PlaceStore;
import com.picturejournal.domain.place.vo.ShareIntakeStatus;
import com.picturejournal.domain.place.vo.VisitStatus;
import com.picturejournal.global.error.ErrorCode;
import com.picturejournal.global.exception.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 외부 공유 원문을 실제 저장 장소로 확정하는 전체 처리 흐름을 담당한다.
 *
 * <p>원문 URL·제목·본문에서 규칙 기반 장소 후보를 추출하고 후보 개수에 따라
 * NEEDS_MANUAL_FIX, NEEDS_CONFIRMATION 또는 NEEDS_SELECTION 상태를 정한다.
 * 사용자는 추출 후보를 선택하거나 수동 장소 정보를 입력해 수집 항목을 RESOLVED로 완료한다.</p>
 *
 * <p>폴더에 연결된 데이터는 REELS_PLACE 폴더에서만 사용할 수 있다.
 * 조회에는 멤버십, 수정·삭제에는 {@link FolderCapabilityPolicy}가 확인하는 쓰기 권한이 필요하다.
 * 수집 항목과 후보, 확정 장소의 저장은 {@link PlaceStore}에 위임한다.</p>
 */
@Service
public class PlaceService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final PlaceStore placeStore;
    private final CollaborationStore collaborationStore;
    private final FolderCapabilityPolicy folderCapabilityPolicy;
    private final Clock clock;

    @Autowired
    public PlaceService(PlaceStore placeStore, CollaborationStore collaborationStore, FolderCapabilityPolicy folderCapabilityPolicy) {
        this(placeStore, collaborationStore, folderCapabilityPolicy, Clock.systemUTC());
    }

    PlaceService(PlaceStore placeStore, CollaborationStore collaborationStore, FolderCapabilityPolicy folderCapabilityPolicy, Clock clock) {
        this.placeStore = placeStore;
        this.collaborationStore = collaborationStore;
        this.folderCapabilityPolicy = folderCapabilityPolicy;
        this.clock = clock;
    }

    /**
     * 공유 원문에서 장소 후보를 추출하고 후보 수에 따라 초기 처리 상태를 결정한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 저장된 수집 항목, 추출 후보와 확정 장소 뷰
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public ShareIntakeView createShareIntake(UUID actorId, CreateShareIntakeCommand command) {
        UUID folderId = command.folderId();
        if (folderId != null) {
            requireReelsPlaceMember(actorId, folderId);
        }
        String rawUrl = normalizeOptional(command.rawUrl());
        String rawTitle = normalizeOptional(command.rawTitle());
        String rawText = normalizeOptional(command.rawText());
        if (rawUrl == null && rawTitle == null && rawText == null) {
            throw invalidArgument("At least one raw share payload field is required.");
        }
        Instant now = Instant.now(clock);
        UUID intakeId = UUID.randomUUID();
        List<PlaceCandidate> candidates = extractCandidates(intakeId, rawTitle, rawText);
        // 후보가 없으면 수동 보정, 하나면 확인, 여러 개면 사용자 선택이 필요하다.
        ShareIntakeStatus status = candidates.isEmpty()
                ? ShareIntakeStatus.NEEDS_MANUAL_FIX
                : (candidates.size() == 1 ? ShareIntakeStatus.NEEDS_CONFIRMATION : ShareIntakeStatus.NEEDS_SELECTION);
        ShareIntakeItem intake = ShareIntakeItem.create(
                intakeId,
                folderId,
                actorId,
                normalizeRequired(command.sourceApp(), "sourceApp"),
                normalizeRequired(command.platform(), "platform"),
                normalizeRequired(command.receivedVia(), "receivedVia"),
                rawUrl,
                rawTitle,
                rawText,
                normalizeUrl(rawUrl, rawText),
                status,
                candidates.isEmpty() ? "No place candidate could be extracted." : null,
                now);
        ShareIntakeItem saved = placeStore.saveIntake(intake);
        candidates.forEach(placeStore::saveCandidate);
        return ShareIntakeView.from(saved, candidates, null);
    }

    /**
     * 수신자 또는 폴더 멤버 권한을 확인하고 후보·확정 장소와 함께 수집 항목을 반환한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param intakeId 대상 공유 수집 항목 식별자
     * @return 권한이 확인된 수집 항목 전체 뷰
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public ShareIntakeView getShareIntake(UUID actorId, UUID intakeId) {
        ShareIntakeItem intake = requireIntake(intakeId);
        if (intake.folderId() != null) {
            requireReelsPlaceMember(actorId, intake.folderId());
        } else if (!intake.receivedByUserId().equals(actorId)) {
            throw forbidden("Only the receiving actor can read this unbound intake.");
        }
        SavedPlace resolvedPlace = intake.resolvedPlaceId() == null ? null : requirePlace(intake.resolvedPlaceId());
        return ShareIntakeView.from(intake, placeStore.listCandidatesByIntakeId(intakeId), resolvedPlace);
    }

    /**
     * 확정 전 수집 항목을 임시 저장 상태로 변경한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param intakeId 대상 공유 수집 항목 식별자
     * @return 임시 저장 상태로 변경된 수집 항목
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public synchronized ShareIntakeView saveDraft(UUID actorId, UUID intakeId) {
        ShareIntakeItem intake = requireIntakeForWrite(actorId, intakeId);
        if (intake.status() == ShareIntakeStatus.RESOLVED) {
            throw conflict("Share intake " + intakeId + " is already resolved.");
        }
        ShareIntakeItem saved = placeStore.saveIntake(intake.saveDraft(Instant.now(clock)));
        return ShareIntakeView.from(saved, placeStore.listCandidatesByIntakeId(intakeId), null);
    }

    /**
     * 후보 또는 수동 입력을 확정 장소로 저장하고 수집 항목을 완료 처리한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param intakeId 대상 공유 수집 항목 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 완료된 수집 항목과 새로 저장된 장소
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public synchronized ResolveShareIntakeResult resolveShareIntake(UUID actorId, UUID intakeId, ResolveShareIntakeCommand command) {
        ShareIntakeItem intake = requireIntakeForWrite(actorId, intakeId);
        if (intake.status() == ShareIntakeStatus.RESOLVED) {
            throw conflict("Share intake " + intakeId + " is already resolved.");
        }
        UUID folderId = command.folderId() == null ? intake.folderId() : command.folderId();
        requireReelsPlaceMember(actorId, requireUuid(folderId, "folderId"));
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        List<PlaceCandidate> candidates = placeStore.listCandidatesByIntakeId(intakeId);
        // 명시적 후보 선택, 수동 입력, 단일 후보 자동 선택 순으로 확정 값을 결정한다.
        ResolvedPlaceFields fields = resolvePlaceFields(command, candidates);
        Instant now = Instant.now(clock);
        SavedPlace place = new SavedPlace(
                UUID.randomUUID(),
                folderId,
                actorId,
                intakeId,
                fields.name(),
                normalizeOptional(command.category()),
                fields.address(),
                normalizeOptional(command.regionText()),
                fields.latitude(),
                fields.longitude(),
                normalizeOptional(command.summary()),
                normalizeOptional(command.whyRecommended()),
                normalizeKeywords(command.keywords()),
                command.visitStatus() == null ? VisitStatus.WANT_TO_GO : command.visitStatus(),
                now,
                now,
                now);
        SavedPlace savedPlace = placeStore.savePlace(place);
        // 확정 장소의 식별자를 수집 항목에 기록해야 같은 공유 건이 다시 처리되는 것을 막을 수 있다.
        ShareIntakeItem resolvedIntake = placeStore.saveIntake(intake.resolve(savedPlace.placeId(), now));
        return new ResolveShareIntakeResult(ShareIntakeView.from(resolvedIntake, candidates, savedPlace), savedPlace);
    }

    /**
     * 폴더 멤버 권한과 검색 조건을 적용해 저장 장소 목록을 반환한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param folderId 대상 공유 폴더 식별자
     * @param filter 목록 결과에 적용할 선택 검색 조건
     * @return 조건에 맞는 저장 장소 목록
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public List<SavedPlace> listSavedPlaces(UUID actorId, UUID folderId, SavedPlaceFilter filter) {
        requireReelsPlaceMember(actorId, folderId);
        return placeStore.listPlacesByFolderId(folderId).stream()
                .filter(place -> filter == null || filter.matches(place))
                .toList();
    }

    /**
     * 폴더 멤버 권한을 확인하고 저장 장소 하나를 반환한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param placeId 대상 저장 장소 식별자
     * @return 권한이 확인된 저장 장소
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public SavedPlace getSavedPlace(UUID actorId, UUID placeId) {
        SavedPlace place = requirePlace(placeId);
        requireReelsPlaceMember(actorId, place.folderId());
        return place;
    }

    /**
     * 쓰기 권한과 좌표 유효성을 확인한 뒤 저장 장소를 변경한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param placeId 대상 저장 장소 식별자
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 변경 후 저장 장소
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public SavedPlace updateSavedPlace(UUID actorId, UUID placeId, UpdateSavedPlaceCommand command) {
        SavedPlace place = requirePlace(placeId);
        requireReelsPlaceMember(actorId, place.folderId());
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, place.folderId());
        SavedPlace updated = place.update(
                command.name() == null ? place.name() : normalizeRequired(command.name(), "name"),
                command.category() == null ? place.category() : normalizeOptional(command.category()),
                command.address() == null ? place.address() : normalizeOptional(command.address()),
                command.regionText() == null ? place.regionText() : normalizeOptional(command.regionText()),
                command.latitude() == null ? place.latitude() : command.latitude(),
                command.longitude() == null ? place.longitude() : command.longitude(),
                command.summary() == null ? place.summary() : normalizeOptional(command.summary()),
                command.whyRecommended() == null ? place.whyRecommended() : normalizeOptional(command.whyRecommended()),
                command.keywords() == null ? place.keywords() : normalizeKeywords(command.keywords()),
                command.visitStatus() == null ? place.visitStatus() : command.visitStatus(),
                Instant.now(clock));
        validateOptionalLocation(updated.latitude(), updated.longitude());
        return placeStore.savePlace(updated);
    }

    /**
     * 쓰기 권한을 확인한 뒤 저장 장소를 삭제한다.
     *
     * @param actorId 요청을 수행하는 사용자 식별자
     * @param placeId 대상 저장 장소 식별자
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public void deleteSavedPlace(UUID actorId, UUID placeId) {
        SavedPlace place = requirePlace(placeId);
        requireReelsPlaceMember(actorId, place.folderId());
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, place.folderId());
        placeStore.deletePlace(placeId);
    }

    private ShareIntakeItem requireIntakeForWrite(UUID actorId, UUID intakeId) {
        ShareIntakeItem intake = requireIntake(intakeId);
        if (intake.folderId() != null) {
            requireReelsPlaceMember(actorId, intake.folderId());
        } else if (!intake.receivedByUserId().equals(actorId)) {
            throw forbidden("Only the receiving actor can write this unbound intake.");
        }
        return intake;
    }

    private ShareIntakeItem requireIntake(UUID intakeId) {
        return placeStore.findIntakeById(intakeId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Share intake " + intakeId + " was not found."));
    }

    private SavedPlace requirePlace(UUID placeId) {
        return placeStore.findPlaceById(placeId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Saved place " + placeId + " was not found."));
    }

    private Folder requireReelsPlaceMember(UUID actorId, UUID folderId) {
        Folder folder = collaborationStore.findFolderById(folderId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Folder " + folderId + " was not found."));
        if (folder.type() != FolderType.REELS_PLACE) {
            throw invalidArgument("Saved places can only be used in REELS_PLACE folders.");
        }
        collaborationStore.findMembership(folderId, actorId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Actor " + actorId + " is not a member of folder " + folderId + "."));
        return folder;
    }

    private List<PlaceCandidate> extractCandidates(UUID intakeId, String rawTitle, String rawText) {
        List<String> names = new ArrayList<>();
        collectCandidateNames(names, rawTitle);
        collectCandidateNames(names, rawText);
        Map<String, String> uniqueNames = new LinkedHashMap<>();
        for (String name : names) {
            String normalized = normalizeOptional(name);
            if (normalized != null && !looksLikeUrl(normalized)) {
                uniqueNames.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
            }
        }
        List<PlaceCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (String name : uniqueNames.values()) {
            candidates.add(new PlaceCandidate(UUID.randomUUID(), intakeId, "rules", name, null, null, null,
                    Math.max(0.5, 0.95 - (index * 0.1)), "{\"source\":\"rules\"}"));
            index++;
        }
        return candidates;
    }

    private void collectCandidateNames(List<String> names, String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return;
        }
        String[] pieces = normalized.split("\\r?\\n|;|\\|");
        for (String piece : pieces) {
            String trimmed = normalizeOptional(piece);
            if (trimmed == null) {
                continue;
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("place:")) {
                names.add(trimmed.substring("place:".length()).trim());
            } else if (!looksLikeUrl(trimmed) && trimmed.length() <= 80) {
                names.add(trimmed);
            }
        }
    }

    private ResolvedPlaceFields resolvePlaceFields(ResolveShareIntakeCommand command, List<PlaceCandidate> candidates) {
        if (command.candidateId() != null) {
            PlaceCandidate candidate = candidates.stream()
                    .filter(item -> item.candidateId().equals(command.candidateId()))
                    .findFirst()
                    .orElseThrow(() -> invalidArgument("candidateId does not belong to this intake."));
            return new ResolvedPlaceFields(candidate.name(), candidate.address(), candidate.latitude(), candidate.longitude());
        }
        if (command.manualName() != null) {
            validateOptionalLocation(command.latitude(), command.longitude());
            return new ResolvedPlaceFields(
                    normalizeRequired(command.manualName(), "manualName"),
                    normalizeOptional(command.address()),
                    command.latitude(),
                    command.longitude());
        }
        if (candidates.size() == 1) {
            PlaceCandidate candidate = candidates.getFirst();
            return new ResolvedPlaceFields(candidate.name(), candidate.address(), candidate.latitude(), candidate.longitude());
        }
        throw invalidArgument("ResolveShareIntake requires a candidateId or manualName.");
    }

    private String normalizeUrl(String rawUrl, String rawText) {
        String candidate = rawUrl == null ? firstUrl(rawText) : rawUrl;
        return candidate == null ? null : candidate.trim();
    }

    private String firstUrl(String rawText) {
        if (rawText == null) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(rawText);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean looksLikeUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private UUID requireUuid(UUID value, String fieldName) {
        if (value == null) {
            throw invalidArgument(fieldName + " is required.");
        }
        return value;
    }

    private void validateOptionalLocation(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw invalidArgument("latitude and longitude must be supplied together.");
        }
        if (latitude != null && (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0)) {
            throw invalidArgument("latitude or longitude is out of range.");
        }
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

    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String keyword : keywords) {
            String trimmed = normalizeOptional(keyword);
            if (trimmed != null) {
                normalized.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return List.copyOf(normalized.values());
    }

    private DomainException invalidArgument(String message) {
        return new DomainException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private DomainException forbidden(String message) {
        return new DomainException(ErrorCode.FORBIDDEN, message);
    }

    private DomainException conflict(String message) {
        return new DomainException(ErrorCode.CONFLICT, message);
    }

    private record ResolvedPlaceFields(String name, String address, Double latitude, Double longitude) {
    }
}
