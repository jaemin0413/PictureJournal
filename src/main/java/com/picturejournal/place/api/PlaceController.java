package com.picturejournal.place.api;

import com.picturejournal.auth.application.AuthService;
import com.picturejournal.place.application.PlaceService;
import com.picturejournal.place.domain.PlaceCandidate;
import com.picturejournal.place.domain.SavedPlace;
import com.picturejournal.place.domain.ShareIntakeItem;
import com.picturejournal.place.domain.ShareIntakeStatus;
import com.picturejournal.place.domain.VisitStatus;
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
        @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
})
public class PlaceController {

    private final PlaceService placeService;
    private final AuthService authService;

    public PlaceController(PlaceService placeService, AuthService authService) {
        this.placeService = placeService;
        this.authService = authService;
    }

    @PostMapping("/share-intake")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public ShareIntakeResponse createShareIntake(HttpServletRequest request, @RequestBody CreateShareIntakeRequest requestBody) {
        return ShareIntakeResponse.from(placeService.createShareIntake(resolveActorId(request), new PlaceService.CreateShareIntakeCommand(
                requestBody.folderId(), requestBody.clientIntakeId(), requestBody.rawUrl(), requestBody.rawTitle(), requestBody.rawText(),
                requestBody.sourceApp(), requestBody.platform(), requestBody.receivedVia(), requestBody.contentFingerprint())));
    }

    @GetMapping("/share-intake/{intakeId}")
    @SecurityRequirement(name = "bearerAuth")
    public ShareIntakeResponse getShareIntake(HttpServletRequest request, @PathVariable UUID intakeId) {
        return ShareIntakeResponse.from(placeService.getShareIntake(resolveActorId(request), intakeId));
    }
    @GetMapping("/folders/{folderId}/share-intake/unresolved")
    @SecurityRequirement(name = "bearerAuth")
    public List<ShareIntakeResponse> listUnresolvedShareIntakes(HttpServletRequest request, @PathVariable UUID folderId) {
        return placeService.listUnresolvedShareIntakes(resolveActorId(request), folderId).stream()
                .map(ShareIntakeResponse::from)
                .toList();
    }

    @PatchMapping("/share-intake/{intakeId}")
    @SecurityRequirement(name = "bearerAuth")
    public ShareIntakeResponse updateUnresolvedShareIntake(
            HttpServletRequest request,
            @PathVariable UUID intakeId,
            @RequestBody CreateShareIntakeRequest requestBody) {
        return ShareIntakeResponse.from(placeService.updateUnresolvedShareIntake(resolveActorId(request), intakeId,
                new PlaceService.CreateShareIntakeCommand(
                        requestBody.folderId(), requestBody.clientIntakeId(), requestBody.rawUrl(), requestBody.rawTitle(), requestBody.rawText(),
                        requestBody.sourceApp(), requestBody.platform(), requestBody.receivedVia(), requestBody.contentFingerprint())));
    }


    @PostMapping("/share-intake/{intakeId}/resolve")
    @SecurityRequirement(name = "bearerAuth")
    public ResolveShareIntakeResponse resolveShareIntake(
            HttpServletRequest request,
            @PathVariable UUID intakeId,
            @RequestBody ResolveShareIntakeRequest requestBody) {
        PlaceService.ResolveShareIntakeResult result = placeService.resolveShareIntake(resolveActorId(request), intakeId,
                new PlaceService.ResolveShareIntakeCommand(
                        requestBody.folderId(), requestBody.candidateId(), requestBody.manualName(), requestBody.category(),
                        requestBody.address(), requestBody.regionText(), requestBody.latitude(), requestBody.longitude(),
                        requestBody.summary(), requestBody.whyRecommended(), requestBody.keywords(), requestBody.visitStatus()));
        return ResolveShareIntakeResponse.from(result);
    }

    @GetMapping("/folders/{folderId}/saved-places")
    @SecurityRequirement(name = "bearerAuth")
    public List<SavedPlaceResponse> listSavedPlaces(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) VisitStatus status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "region", required = false) String region) {
        return placeService.listSavedPlaces(resolveActorId(request), folderId, new PlaceService.SavedPlaceFilter(category, status, keyword, region)).stream()
                .map(SavedPlaceResponse::from)
                .toList();
    }

    @GetMapping("/saved-places/{placeId}")
    @SecurityRequirement(name = "bearerAuth")
    public SavedPlaceResponse getSavedPlace(HttpServletRequest request, @PathVariable UUID placeId) {
        return SavedPlaceResponse.from(placeService.getSavedPlace(resolveActorId(request), placeId));
    }

    @PatchMapping("/saved-places/{placeId}")
    @SecurityRequirement(name = "bearerAuth")
    public SavedPlaceResponse updateSavedPlace(
            HttpServletRequest request,
            @PathVariable UUID placeId,
            @RequestBody UpdateSavedPlaceRequest requestBody) {
        return SavedPlaceResponse.from(placeService.updateSavedPlace(resolveActorId(request), placeId,
                new PlaceService.UpdateSavedPlaceCommand(
                        requestBody.name(), requestBody.category(), requestBody.address(), requestBody.regionText(),
                        requestBody.latitude(), requestBody.longitude(), requestBody.summary(), requestBody.whyRecommended(),
                        requestBody.keywords(), requestBody.visitStatus())));
    }

    @DeleteMapping("/saved-places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    public void deleteSavedPlace(HttpServletRequest request, @PathVariable UUID placeId) {
        placeService.deleteSavedPlace(resolveActorId(request), placeId);
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }

    public record CreateShareIntakeRequest(
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

    public record ResolveShareIntakeRequest(
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

    public record UpdateSavedPlaceRequest(
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

    public record ShareIntakeResponse(
            UUID intakeId,
            String clientIntakeId,
            UUID folderId,
            UUID receivedByUserId,
            String sourceApp,
            String platform,
            String receivedVia,
            String rawUrl,
            String rawTitle,
            String rawText,
            String normalizedUrl,
            String contentFingerprint,
            ShareIntakeStatus status,
            String failureReason,
            UUID resolvedPlaceId,
            Instant receivedAt,
            Instant updatedAt,
            Instant resolvedAt,
            List<PlaceCandidateResponse> candidates,
            SavedPlaceResponse resolvedPlace) {

        static ShareIntakeResponse from(PlaceService.ShareIntakeView view) {
            ShareIntakeItem intake = view.intake();
            return new ShareIntakeResponse(
                    intake.intakeId(), intake.clientIntakeId(), intake.folderId(), intake.receivedByUserId(), intake.sourceApp(), intake.platform(),
                    intake.receivedVia(), intake.rawUrl(), intake.rawTitle(), intake.rawText(), intake.normalizedUrl(), intake.contentFingerprint(),
                    intake.status(), intake.failureReason(), intake.resolvedPlaceId(), intake.receivedAt(), intake.updatedAt(), intake.resolvedAt(),
                    view.candidates().stream().map(PlaceCandidateResponse::from).toList(),
                    view.resolvedPlace() == null ? null : SavedPlaceResponse.from(view.resolvedPlace()));
        }
    }

    public record ResolveShareIntakeResponse(ShareIntakeResponse intake, SavedPlaceResponse savedPlace) {

        static ResolveShareIntakeResponse from(PlaceService.ResolveShareIntakeResult result) {
            return new ResolveShareIntakeResponse(ShareIntakeResponse.from(result.intake()), SavedPlaceResponse.from(result.savedPlace()));
        }
    }

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

        static PlaceCandidateResponse from(PlaceCandidate candidate) {
            return new PlaceCandidateResponse(candidate.candidateId(), candidate.intakeId(), candidate.provider(), candidate.name(),
                    candidate.address(), candidate.latitude(), candidate.longitude(), candidate.confidence(), candidate.rawPayloadJson());
        }
    }

    public record SavedPlaceResponse(
            UUID placeId,
            UUID folderId,
            UUID creatorUserId,
            UUID shareIntakeId,
            String name,
            String category,
            String address,
            String regionText,
            Double latitude,
            Double longitude,
            String summary,
            String whyRecommended,
            List<String> keywords,
            VisitStatus visitStatus,
            Instant savedAt,
            Instant updatedAt,
            Instant resolvedAt) {

        static SavedPlaceResponse from(SavedPlace place) {
            return new SavedPlaceResponse(place.placeId(), place.folderId(), place.creatorUserId(), place.shareIntakeId(), place.name(),
                    place.category(), place.address(), place.regionText(), place.latitude(), place.longitude(), place.summary(),
                    place.whyRecommended(), place.keywords(), place.visitStatus(), place.savedAt(), place.updatedAt(), place.resolvedAt());
        }
    }
}
