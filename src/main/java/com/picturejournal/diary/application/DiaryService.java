package com.picturejournal.diary.application;

import com.picturejournal.collaboration.application.CollaborationStore;
import com.picturejournal.collaboration.domain.Folder;
import com.picturejournal.diary.domain.DiaryEntry;
import com.picturejournal.folder.application.FolderCapabilityPolicy;
import com.picturejournal.folder.domain.FolderType;
import com.picturejournal.media.application.MediaService;
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public DiaryService(
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

    public DiaryEntry createEntry(UUID actorId, UUID folderId, CreateDiaryEntryCommand command) {
        requirePhotoDiaryMember(actorId, folderId);
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, folderId);
        MediaAsset mediaAsset = mediaService.requirePendingUpload(actorId, folderId, requireUuid(command.mediaId(), "mediaId"));
        ResolvedLocation location = requireLocation(command.latitude(), command.longitude());
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
        DiaryEntry savedEntry = diaryEntryStore.save(entry);
        try {
            mediaService.commitDiaryMedia(mediaAsset, folderId, savedEntry.entryId());
        } catch (RuntimeException exception) {
            try {
                diaryEntryStore.delete(savedEntry.entryId());
            } catch (RuntimeException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        }
        return savedEntry;
    }

    public List<DiaryEntry> listEntries(UUID actorId, UUID folderId, DiaryEntryFilter filter) {
        requirePhotoDiaryMember(actorId, folderId);
        return diaryEntryStore.listByFolderId(folderId).stream()
                .filter(entry -> filter == null || filter.matches(entry))
                .toList();
    }

    public DiaryEntry getEntry(UUID actorId, UUID entryId) {
        DiaryEntry entry = requireEntry(entryId);
        requirePhotoDiaryMember(actorId, entry.folderId());
        return entry;
    }

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

    public void deleteEntry(UUID actorId, UUID entryId) {
        DiaryEntry entry = requireEntry(entryId);
        requirePhotoDiaryMember(actorId, entry.folderId());
        folderCapabilityPolicy.assertCanWriteToFolder(actorId, entry.folderId());
        diaryEntryStore.delete(entryId);
    }

    private void requirePhotoDiaryMember(UUID actorId, UUID folderId) {
        Folder folder = collaborationStore.findFolderById(folderId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "Folder " + folderId + " was not found."));
        if (folder.type() != FolderType.PHOTO_DIARY) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Diary entries can only be used in PHOTO_DIARY folders.");
        }
        collaborationStore.findMembership(folderId, actorId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Actor " + actorId + " is not a member of folder " + folderId + "."));
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


    private ResolvedLocation resolveUpdatedLocation(Double latitude, Double longitude, DiaryEntry entry) {
        double resolvedLatitude = latitude == null ? entry.latitude() : latitude;
        double resolvedLongitude = longitude == null ? entry.longitude() : longitude;
        return requireLocation(resolvedLatitude, resolvedLongitude);
    }

    private ResolvedLocation requireLocation(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw invalidArgument("final latitude and longitude are required.");
        }
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
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

    public record CreateDiaryEntryCommand(
            UUID mediaId,
            String title,
            String body,
            String placeName,
            Double latitude,
            Double longitude,
            Instant capturedAt,
            List<String> tags) {
    }

    public record UpdateDiaryEntryCommand(
            String title,
            String body,
            String placeName,
            Double latitude,
            Double longitude,
            Instant capturedAt,
            List<String> tags) {
    }

    public record DiaryEntryFilter(String tag, String place, Instant from, Instant to) {

        boolean matches(DiaryEntry entry) {
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
}
