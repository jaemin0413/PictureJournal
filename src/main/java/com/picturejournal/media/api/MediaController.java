package com.picturejournal.media.api;

import com.picturejournal.auth.application.AuthService;
import com.picturejournal.media.application.MediaService;
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
})
public class MediaController {

    private final MediaService mediaService;
    private final AuthService authService;

    public MediaController(MediaService mediaService, AuthService authService) {
        this.mediaService = mediaService;
        this.authService = authService;
    }

    @PostMapping("/direct-upload")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public MediaResponse uploadDirect(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        return MediaResponse.from(mediaService.uploadDirect(resolveActorId(request), file));
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }

    public record MediaResponse(
            UUID mediaId,
            String storageKey,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            String exifJson,
            Instant takenAt,
            String cameraMake,
            String cameraModel,
            Double gpsLatitude,
            Double gpsLongitude,
            Instant createdAt) {

        static MediaResponse from(MediaAsset mediaAsset) {
            return new MediaResponse(
                    mediaAsset.mediaId(),
                    mediaAsset.storageKey(),
                    mediaAsset.originalFilename(),
                    mediaAsset.mimeType(),
                    mediaAsset.sizeBytes(),
                    mediaAsset.width(),
                    mediaAsset.height(),
                    mediaAsset.exifJson(),
                    mediaAsset.takenAt(),
                    mediaAsset.cameraMake(),
                    mediaAsset.cameraModel(),
                    mediaAsset.gpsLatitude(),
                    mediaAsset.gpsLongitude(),
                    mediaAsset.createdAt());
        }
    }
}
