package com.picturejournal.media.api;

import com.picturejournal.auth.application.AuthService;
import com.picturejournal.media.application.MediaService;
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
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

    @PostMapping(value = "/direct-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Upload one pending diary photo",
            requestBody = @RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = DirectUploadRequest.class))))
    public MediaResponse uploadDirect(
            HttpServletRequest request,
            @RequestParam("intendedFolderId") UUID intendedFolderId,
            @RequestParam("file") MultipartFile file) {
        return MediaResponse.from(mediaService.uploadDirect(resolveActorId(request), intendedFolderId, file));
    }

    @GetMapping("/{mediaId}/binary")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<byte[]> readBinary(HttpServletRequest request, @PathVariable UUID mediaId) {
        MediaService.BinaryMedia binaryMedia = mediaService.readAuthorizedBinary(resolveActorId(request), mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(binaryMedia.mediaAsset().mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename(binaryMedia.mediaAsset()) + "\"")
                .body(binaryMedia.bytes());
    }

    @PostMapping("/pending/cleanup")
    @SecurityRequirement(name = "bearerAuth")
    public MediaService.CleanupResult cleanupPending(HttpServletRequest request) {
        resolveActorId(request);
        return new MediaService.CleanupResult(mediaService.cleanupExpiredPending());
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }

    private String filename(MediaAsset mediaAsset) {
        if (mediaAsset.originalFilename() != null) {
            return mediaAsset.originalFilename().replace("\"", "");
        }
        return mediaAsset.mediaId() + ("image/png".equals(mediaAsset.mimeType()) ? ".png" : ".jpg");
    }

    public record MediaResponse(
            UUID mediaId,
            UUID intendedFolderId,
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
            String checksumSha256,
            MediaAsset.Status status,
            UUID committedFolderId,
            UUID committedDiaryEntryId,
            Instant createdAt,
            Instant pendingExpiresAt,
            Instant committedAt) {

        static MediaResponse from(MediaAsset mediaAsset) {
            return new MediaResponse(
                    mediaAsset.mediaId(),
                    mediaAsset.intendedFolderId(),
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
                    mediaAsset.checksumSha256(),
                    mediaAsset.status(),
                    mediaAsset.committedFolderId(),
                    mediaAsset.committedDiaryEntryId(),
                    mediaAsset.createdAt(),
                    mediaAsset.pendingExpiresAt(),
                    mediaAsset.committedAt());
        }
    }

    private record DirectUploadRequest(
            @Schema(type = "string", format = "binary") MultipartFile file,
            @Schema(type = "string", format = "uuid") UUID intendedFolderId) {
    }
}
