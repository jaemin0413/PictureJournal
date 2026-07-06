package com.picturejournal.place.application;

import com.picturejournal.collaboration.application.CollaborationStore;
import com.picturejournal.collaboration.domain.Folder;
import com.picturejournal.folder.application.FolderCapabilityPolicy;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.place.domain.PlaceCandidate;
import com.picturejournal.place.domain.SavedPlace;
import com.picturejournal.place.domain.ShareIntakeItem;
import com.picturejournal.place.domain.ShareIntakeStatus;
import com.picturejournal.place.domain.VisitStatus;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public synchronized ShareIntakeView saveDraft(UUID actorId, UUID intakeId) {
        ShareIntakeItem intake = requireIntakeForWrite(actorId, intakeId);
        if (intake.status() == ShareIntakeStatus.RESOLVED) {
            throw conflict("Share intake " + intakeId + " is already resolved.");
        }
        ShareIntakeItem saved = placeStore.saveIntake(intake.saveDraft(Instant.now(clock)));
        return ShareIntakeView.from(saved, placeStore.listCandidatesByIntakeId(intakeId), null);
    }

    public synchronized ResolveShareIntakeResult resolveShareIntake(UUID actorId, UUID intakeId, ResolveShareIntakeCommand command) {
        ShareIntakeItem intake = requireIntakeForWrite(actorId, intakeId);
        if (intake.status() == ShareIntakeStatus.RESOLVED) {
            throw conflict("Share intake " + intakeId + " is already resolved.");
        }
        UUID folderId = command.folderId() == null ? intake.folderId() : command.folderId();
        requireReelsPlaceMember(actorId, requireUuid(folderId, "folderId"));
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        List<PlaceCandidate> candidates = placeStore.listCandidatesByIntakeId(intakeId);
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
        ShareIntakeItem resolvedIntake = placeStore.saveIntake(intake.resolve(savedPlace.placeId(), now));
        return new ResolveShareIntakeResult(ShareIntakeView.from(resolvedIntake, candidates, savedPlace), savedPlace);
    }

    public List<SavedPlace> listSavedPlaces(UUID actorId, UUID folderId, SavedPlaceFilter filter) {
        requireReelsPlaceMember(actorId, folderId);
        return placeStore.listPlacesByFolderId(folderId).stream()
                .filter(place -> filter == null || filter.matches(place))
                .toList();
    }

    public SavedPlace getSavedPlace(UUID actorId, UUID placeId) {
        SavedPlace place = requirePlace(placeId);
        requireReelsPlaceMember(actorId, place.folderId());
        return place;
    }

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

    public record CreateShareIntakeCommand(
            UUID folderId,
            String rawUrl,
            String rawTitle,
            String rawText,
            String sourceApp,
            String platform,
            String receivedVia) {
    }

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

    public record UpdateSavedPlaceCommand(
            String name,
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

    public record SavedPlaceFilter(String category, VisitStatus status, String keyword, String region) {

        boolean matches(SavedPlace place) {
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

    public record ShareIntakeView(ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace) {

        static ShareIntakeView from(ShareIntakeItem intake, List<PlaceCandidate> candidates, SavedPlace resolvedPlace) {
            return new ShareIntakeView(intake, List.copyOf(candidates), resolvedPlace);
        }
    }

    public record ResolveShareIntakeResult(ShareIntakeView intake, SavedPlace savedPlace) {
    }
}
