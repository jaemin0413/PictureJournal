package com.picturejournal.domain.media.controller;

import com.picturejournal.domain.auth.service.AuthService;
import com.picturejournal.domain.media.dto.response.MediaResponse;
import com.picturejournal.domain.media.service.MediaService;
import com.picturejournal.global.dto.response.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 인증된 사용자의 미디어 업로드 및 조회 요청을 처리한다.
 */
@RestController
@RequestMapping("/api/v1/media")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class MediaController {

    private final MediaService mediaService;
    private final AuthService authService;

    public MediaController(MediaService mediaService, AuthService authService) {
        this.mediaService = mediaService;
        this.authService = authService;
    }

    /**
     * 업로드 파일을 검증하고 EXIF 정보를 추출한 뒤 바이너리와 메타데이터를 저장한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param file 검증하고 저장할 업로드 파일
     * @return 저장된 미디어 메타데이터
     */
    @PostMapping("/direct-upload")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public MediaResponse uploadDirect(HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        return MediaResponse.from(mediaService.uploadDirect(resolveActorId(request), file));
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }
}
