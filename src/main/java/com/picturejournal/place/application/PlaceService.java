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

    private static final int DEDUPE_WINDOW_SECONDS = 300;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private final PlaceStore placeStore;
    private final CollaborationStore collaborationStore;
    private final FolderCapabilityPolicy folderCapabilityPolicy;
    private final Clock clock;

    @Autowired
    public PlaceService(PlaceStore placeStore, CollaborationStore collaborationStore, FolderCapabilityPolicy folderCapabilityPolicy) {
        this(placeStore, collaborationStore, folderCapabilityPolicy, Clock.systemUTC());
    }

    public PlaceService(PlaceStore placeStore, CollaborationStore collaborationStore, FolderCapabilityPolicy folderCapabilityPolicy, Clock clock) {
        this.placeStore = placeStore;
        this.collaborationStore = collaborationStore;
        this.folderCapabilityPolicy = folderCapabilityPolicy;
        this.clock = clock;
    }

    public synchronized ShareIntakeView createShareIntake(UUID actorId, CreateShareIntakeCommand command) {
        UUID folderId = requireUuid(command.folderId(), "folderId");
        requireReelsPlaceMember(actorId, folderId);
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        String clientIntakeId = normalizeRequired(command.clientIntakeId(), "clientIntakeId");
        String rawUrl = normalizeOptional(command.rawUrl());
        String rawTitle = normalizeOptional(command.rawTitle());
        String rawText = normalizeOptional(command.rawText());
        if (rawUrl == null && rawTitle == null && rawText == null) {
            throw invalidArgument("At least one raw share payload field is required.");
        }
        String normalizedUrl = normalizeUrl(rawUrl, rawText);
        String contentFingerprint = requireClientFingerprint(command.contentFingerprint());
        String sourceApp = normalizeRequired(command.sourceApp(), "sourceApp");
        String platform = normalizeRequired(command.platform(), "platform");
        String receivedVia = normalizeRequired(command.receivedVia(), "receivedVia");
        String createIdentity = createIdentity(folderId, actorId, clientIntakeId, rawUrl, rawTitle, rawText, normalizedUrl,
                contentFingerprint, sourceApp, platform, receivedVia);
        Instant now = Instant.now(clock);
        ShareIntakeItem existingByClientId = placeStore.findIntakeByCreateReceipt(actorId, folderId, clientIntakeId).orElse(null);
        if (existingByClientId != null) {
            String existingIdentity = existingByClientId.clientIntakeId().equals(clientIntakeId)
                    ? existingByClientId.createIdentity()
                    : existingByClientId.createReceipts().stream()
                            .filter(receipt -> clientIntakeId.equals(receipt.clientIntakeId()))
                            .findFirst()
                            .orElseThrow()
                            .createIdentity();
            if (!createIdentity.equals(existingIdentity)) {
                throw conflict("clientIntakeId is already used for a different share intake request.");
            }
            return viewFor(existingByClientId);
        }
        ShareIntakeItem fingerprintDuplicate = findFingerprintDuplicate(actorId, folderId, contentFingerprint, now);
        if (fingerprintDuplicate != null) {
            PlaceStore.AggregateRead aggregate = placeStore.saveCreateReceipt(fingerprintDuplicate.intakeId(),
                    new ShareIntakeItem.CreateReceipt(actorId, folderId, clientIntakeId, createIdentity));
            return ShareIntakeView.from(aggregate.intake(), aggregate.candidates(), aggregate.resolvedPlace());
        }
        UUID intakeId = UUID.randomUUID();
        List<PlaceCandidate> candidates = extractCandidates(intakeId, rawTitle, rawText);
        String failureReason = candidates.size() == 1 ? null : unresolvedReason(candidates);
        ShareIntakeItem intake = ShareIntakeItem.create(
                intakeId, clientIntakeId, folderId, actorId, sourceApp, platform, receivedVia, rawUrl, rawTitle, rawText,
                normalizedUrl, contentFingerprint, createIdentity, failureReason, now);
        SavedPlace resolvedPlace = candidates.size() == 1 ? autoSavePlace(intake, candidates.getFirst(), now) : null;
        ShareIntakeItem persistedIntake = resolvedPlace == null
                ? intake
                : intake.resolve(resolvedPlace.placeId(), autoResolutionIdentity(candidates.getFirst()), now);
        PlaceStore.AggregateWrite aggregate = placeStore.saveIntakeAggregate(persistedIntake, candidates, resolvedPlace);
        return ShareIntakeView.from(aggregate.intake(), aggregate.candidates(), aggregate.resolvedPlace());
    }

    public ShareIntakeView getShareIntake(UUID actorId, UUID intakeId) {
        PlaceStore.AggregateRead aggregate = placeStore.findAggregateByIntakeId(intakeId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Share intake " + intakeId + " was not found."));
        requireReelsPlaceMember(actorId, aggregate.intake().folderId());
        return ShareIntakeView.from(aggregate.intake(), aggregate.candidates(), aggregate.resolvedPlace());
    }
    public List<ShareIntakeView> listUnresolvedShareIntakes(UUID actorId, UUID folderId) {
        requireReelsPlaceMember(actorId, folderId);
        return placeStore.listAggregatesByFolderId(folderId).stream()
                .filter(aggregate -> aggregate.intake().status() != ShareIntakeStatus.RESOLVED)
                .map(aggregate -> ShareIntakeView.from(
                        aggregate.intake(), aggregate.candidates(), aggregate.resolvedPlace()))
                .toList();
    }

    public synchronized ShareIntakeView updateUnresolvedShareIntake(UUID actorId, UUID intakeId, UpdateShareIntakeCommand command) {
        PlaceStore.AggregateRead current = requireAggregateForWrite(actorId, intakeId);
        ShareIntakeItem intake = current.intake();
        if (intake.status() == ShareIntakeStatus.RESOLVED) {
            throw conflict("Share intake " + intakeId + " is already resolved.");
        }
        String rawUrl = normalizeOptional(command.rawUrl());
        String rawTitle = normalizeOptional(command.rawTitle());
        String rawText = normalizeOptional(command.rawText());
        if (rawUrl == null && rawTitle == null && rawText == null) {
            throw invalidArgument("At least one raw share payload field is required.");
        }
        Instant now = Instant.now(clock);
        List<PlaceCandidate> candidates = extractCandidates(intakeId, rawTitle, rawText);
        ShareIntakeItem updated = intake.updateUnresolved(rawUrl, rawTitle, rawText, normalizeUrl(rawUrl, rawText),
                unresolvedReason(candidates), now);
        PlaceStore.AggregateWrite aggregate = placeStore.saveIntakeAggregate(updated, candidates, null);
        return ShareIntakeView.from(aggregate.intake(), aggregate.candidates(), null);
    }


    public synchronized ResolveShareIntakeResult resolveShareIntake(UUID actorId, UUID intakeId, ResolveShareIntakeCommand command) {
        PlaceStore.AggregateRead current = requireAggregateForWrite(actorId, intakeId);
        ShareIntakeItem intake = current.intake();
        if (command.folderId() != null && !intake.folderId().equals(command.folderId())) {
            throw invalidArgument("A share intake must be resolved in its original folder.");
        }
        List<PlaceCandidate> candidates = current.candidates();
        ResolvedPlaceFields fields = resolvePlaceFields(command, candidates);
        String resolutionIdentity = resolutionIdentity(command, fields);
        if (intake.status() == ShareIntakeStatus.RESOLVED) {
            if (!intake.resolutionIdentity().equals(resolutionIdentity)) {
                throw conflict("Share intake " + intakeId + " is already resolved differently.");
            }
            return new ResolveShareIntakeResult(
                    ShareIntakeView.from(intake, candidates, current.resolvedPlace()),
                    current.resolvedPlace());
        }
        UUID folderId = intake.folderId();
        requireReelsPlaceMember(actorId, folderId);
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
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
        PlaceStore.ResolutionWrite resolution = placeStore.saveResolution(intake.resolve(place.placeId(), resolutionIdentity, now), place);
        return new ResolveShareIntakeResult(
                ShareIntakeView.from(resolution.intake(), candidates, resolution.place()),
                resolution.place());
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
        if (place.shareIntakeId() != null) {
            throw conflict("A saved place created from a share intake cannot be deleted while its intake is retained.");
        }
        placeStore.deletePlace(placeId);
    }

    private PlaceStore.AggregateRead requireAggregateForWrite(UUID actorId, UUID intakeId) {
        PlaceStore.AggregateRead aggregate = placeStore.findAggregateByIntakeId(intakeId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Share intake " + intakeId + " was not found."));
        requireReelsPlaceMember(actorId, aggregate.intake().folderId());
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, aggregate.intake().folderId());
        return aggregate;
    }

    private SavedPlace requirePlace(UUID placeId) {
        return placeStore.findPlaceById(placeId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Saved place " + placeId + " was not found."));
    }

    private void requireReelsPlaceMember(UUID actorId, UUID folderId) {
        Folder folder = collaborationStore.findFolderById(folderId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Folder " + folderId + " was not found."));
        if (folder.type() != FolderType.REELS_PLACE) {
            throw invalidArgument("Saved places can only be used in REELS_PLACE folders.");
        }
        collaborationStore.findMembership(folderId, actorId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Actor " + actorId + " is not a member of folder " + folderId + "."));
    }
    private ShareIntakeItem findFingerprintDuplicate(UUID actorId, UUID folderId, String contentFingerprint, Instant now) {
        Instant dedupeCutoff = now.minusSeconds(DEDUPE_WINDOW_SECONDS);
        return placeStore.listIntakesByFolderId(folderId).stream()
                .filter(intake -> actorId.equals(intake.receivedByUserId()))
                .filter(intake -> contentFingerprint.equals(intake.contentFingerprint()))
                .filter(intake -> !intake.receivedAt().isBefore(dedupeCutoff))
                .findFirst()
                .orElse(null);
    }

    private ShareIntakeView viewFor(ShareIntakeItem intake) {
        PlaceStore.AggregateRead aggregate = placeStore.findAggregateByIntakeId(intake.intakeId())
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Share intake " + intake.intakeId() + " was not found."));
        return ShareIntakeView.from(aggregate.intake(), aggregate.candidates(), aggregate.resolvedPlace());
    }

    private SavedPlace autoSavePlace(ShareIntakeItem intake, PlaceCandidate candidate, Instant now) {
        SavedPlace place = new SavedPlace(
                UUID.randomUUID(),
                intake.folderId(),
                intake.receivedByUserId(),
                intake.intakeId(),
                candidate.name(),
                null,
                candidate.address(),
                null,
                candidate.latitude(),
                candidate.longitude(),
                null,
                null,
                List.of(),
                VisitStatus.WANT_TO_GO,
                now,
                now,
                now);
        return place;
    }

    private String unresolvedReason(List<PlaceCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "No trusted place candidate could be extracted.";
        }
        if (candidates.size() > 1) {
            return "Multiple possible place candidates require repair.";
        }
        return null;
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
            if (trimmed != null && trimmed.regionMatches(true, 0, "place:", 0, "place:".length())) {
                String name = normalizeOptional(trimmed.substring("place:".length()));
                if (name != null) {
                    names.add(name);
                }
            }
        }
    }

    private ResolvedPlaceFields resolvePlaceFields(ResolveShareIntakeCommand command, List<PlaceCandidate> candidates) {
        boolean hasManualPlaceInput = command.manualName() != null || command.address() != null
                || command.latitude() != null || command.longitude() != null;
        if (command.candidateId() != null) {
            if (hasManualPlaceInput) {
                throw invalidArgument("candidateId cannot be combined with manual place fields.");
            }
            PlaceCandidate candidate = candidates.stream()
                    .filter(item -> item.candidateId().equals(command.candidateId()))
                    .findFirst()
                    .orElseThrow(() -> invalidArgument("candidateId does not belong to this intake."));
            return new ResolvedPlaceFields(candidate.candidateId(), candidate.name(), candidate.address(), candidate.latitude(), candidate.longitude());
        }
        if (command.manualName() != null) {
            validateOptionalLocation(command.latitude(), command.longitude());
            return new ResolvedPlaceFields(
                    null,
                    normalizeRequired(command.manualName(), "manualName"),
                    normalizeOptional(command.address()),
                    command.latitude(),
                    command.longitude());
        }
        if (hasManualPlaceInput) {
            throw invalidArgument("manualName is required when manual place fields are supplied.");
        }
        throw invalidArgument("ResolveShareIntake requires a candidateId or manualName.");
    }

    private String createIdentity(
            UUID folderId,
            UUID actorId,
            String clientIntakeId,
            String rawUrl,
            String rawTitle,
            String rawText,
            String normalizedUrl,
            String contentFingerprint,
            String sourceApp,
            String platform,
            String receivedVia) {
        return canonicalIdentity("create", folderId.toString(), actorId.toString(), clientIntakeId, rawUrl, rawTitle, rawText,
                normalizedUrl, contentFingerprint, sourceApp, platform, receivedVia);
    }

    private String autoResolutionIdentity(PlaceCandidate candidate) {
        return canonicalIdentity("candidate", candidate.candidateId().toString(), null, null, null, null, "",
                VisitStatus.WANT_TO_GO.name());
    }

    private String resolutionIdentity(ResolveShareIntakeCommand command, ResolvedPlaceFields fields) {
        String category = normalizeOptional(command.category());
        String regionText = normalizeOptional(command.regionText());
        String summary = normalizeOptional(command.summary());
        String whyRecommended = normalizeOptional(command.whyRecommended());
        String keywords = keywordIdentity(normalizeKeywords(command.keywords()));
        String visitStatus = (command.visitStatus() == null ? VisitStatus.WANT_TO_GO : command.visitStatus()).name();
        if (fields.candidateId() != null) {
            return canonicalIdentity("candidate", fields.candidateId().toString(), category, regionText, summary, whyRecommended, keywords, visitStatus);
        }
        return canonicalIdentity("manual", fields.name(), fields.address(), numberIdentity(fields.latitude()), numberIdentity(fields.longitude()),
                category, regionText, summary, whyRecommended, keywords, visitStatus);
    }

    private String canonicalIdentity(String type, String... values) {
        StringBuilder identity = new StringBuilder(type);
        for (String value : values) {
            identity.append('|');
            if (value == null) {
                identity.append("-1:");
            } else {
                identity.append(value.length()).append(':').append(value);
            }
        }
        return identity.toString();
    }
    private String keywordIdentity(List<String> keywords) {
        StringBuilder identity = new StringBuilder().append(keywords.size()).append(':');
        for (String keyword : keywords) {
            identity.append(keyword.length()).append(':').append(keyword);
        }
        return identity.toString();
    }

    private String numberIdentity(Double value) {
        return value == null ? null : Double.toString(value);
    }

    private String normalizeUrl(String rawUrl, String rawText) {
        String candidate = rawUrl == null ? firstUrl(rawText) : rawUrl;
        return candidate == null ? null : candidate.trim();
    }

    private String requireClientFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw invalidArgument("contentFingerprint must be exactly 64 hexadecimal characters.");
        }
        return value.toLowerCase(Locale.ROOT);
    }


    private String firstUrl(String rawText) {
        if (rawText == null) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(rawText);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean looksLikeUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
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
        if (latitude != null && (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0)) {
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


    private DomainException conflict(String message) {
        return new DomainException(ErrorCode.CONFLICT, message);
    }

    private record ResolvedPlaceFields(UUID candidateId, String name, String address, Double latitude, Double longitude) {
    }

    public record CreateShareIntakeCommand(
            UUID folderId,
            String clientIntakeId,
            String rawUrl,
            String rawTitle,
            String rawText,
            String sourceApp,
            String platform,
            String receivedVia,
            String contentFingerprint) {
    }

    public record UpdateShareIntakeCommand(String rawUrl, String rawTitle, String rawText) {
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
