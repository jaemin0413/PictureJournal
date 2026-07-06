package com.picturejournal.diary.api;

import com.picturejournal.auth.application.AuthService;
import com.picturejournal.diary.application.DiaryService;
import com.picturejournal.diary.domain.DiaryEntry;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
})
public class DiaryController {

    private final DiaryService diaryService;
    private final AuthService authService;

    public DiaryController(DiaryService diaryService, AuthService authService) {
        this.diaryService = diaryService;
        this.authService = authService;
    }

    @PostMapping("/folders/{folderId}/diary-entries")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public DiaryEntryResponse createEntry(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestBody UpsertDiaryEntryRequest requestBody) {
        return DiaryEntryResponse.from(diaryService.createEntry(
                resolveActorId(request),
                folderId,
                new DiaryService.CreateDiaryEntryCommand(
                        requestBody.mediaId(),
                        requestBody.title(),
                        requestBody.body(),
                        requestBody.placeName(),
                        requestBody.latitude(),
                        requestBody.longitude(),
                        requestBody.capturedAt(),
                        requestBody.tags())));
    }

    @GetMapping("/folders/{folderId}/diary-entries")
    @SecurityRequirement(name = "bearerAuth")
    public List<DiaryEntryResponse> listEntries(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "place", required = false) String place,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return diaryService.listEntries(resolveActorId(request), folderId, new DiaryService.DiaryEntryFilter(tag, place, from, to)).stream()
                .map(DiaryEntryResponse::from)
                .toList();
    }

    @GetMapping("/folders/{folderId}/diary-entries/map")
    @SecurityRequirement(name = "bearerAuth")
    public List<DiaryMapEntryResponse> listMapEntries(HttpServletRequest request, @PathVariable UUID folderId) {
        return diaryService.listEntries(resolveActorId(request), folderId, new DiaryService.DiaryEntryFilter(null, null, null, null)).stream()
                .map(DiaryMapEntryResponse::from)
                .toList();
    }

    @GetMapping("/diary-entries/{entryId}")
    @SecurityRequirement(name = "bearerAuth")
    public DiaryEntryResponse getEntry(HttpServletRequest request, @PathVariable UUID entryId) {
        return DiaryEntryResponse.from(diaryService.getEntry(resolveActorId(request), entryId));
    }

    @PatchMapping("/diary-entries/{entryId}")
    @SecurityRequirement(name = "bearerAuth")
    public DiaryEntryResponse updateEntry(
            HttpServletRequest request,
            @PathVariable UUID entryId,
            @RequestBody UpsertDiaryEntryRequest requestBody) {
        return DiaryEntryResponse.from(diaryService.updateEntry(
                resolveActorId(request),
                entryId,
                new DiaryService.UpdateDiaryEntryCommand(
                        requestBody.title(),
                        requestBody.body(),
                        requestBody.placeName(),
                        requestBody.latitude(),
                        requestBody.longitude(),
                        requestBody.capturedAt(),
                        requestBody.tags())));
    }

    @DeleteMapping("/diary-entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void deleteEntry(HttpServletRequest request, @PathVariable UUID entryId) {
        diaryService.deleteEntry(resolveActorId(request), entryId);
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }

    public record UpsertDiaryEntryRequest(
            UUID mediaId,
            String title,
            String body,
            String placeName,
            Double latitude,
            Double longitude,
            Instant capturedAt,
            List<String> tags) {
    }

    public record DiaryEntryResponse(
            UUID entryId,
            UUID folderId,
            UUID authorUserId,
            UUID mediaId,
            String title,
            String body,
            String placeName,
            double latitude,
            double longitude,
            Instant capturedAt,
            String visibilityMode,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt) {

        static DiaryEntryResponse from(DiaryEntry entry) {
            return new DiaryEntryResponse(
                    entry.entryId(),
                    entry.folderId(),
                    entry.authorUserId(),
                    entry.mediaId(),
                    entry.title(),
                    entry.body(),
                    entry.placeName(),
                    entry.latitude(),
                    entry.longitude(),
                    entry.capturedAt(),
                    entry.visibilityMode(),
                    entry.tags(),
                    entry.createdAt(),
                    entry.updatedAt());
        }
    }

    public record DiaryMapEntryResponse(
            UUID entryId,
            String title,
            String placeName,
            double latitude,
            double longitude,
            Instant capturedAt) {

        static DiaryMapEntryResponse from(DiaryEntry entry) {
            return new DiaryMapEntryResponse(
                    entry.entryId(),
                    entry.title(),
                    entry.placeName(),
                    entry.latitude(),
                    entry.longitude(),
                    entry.capturedAt());
        }
    }
}
