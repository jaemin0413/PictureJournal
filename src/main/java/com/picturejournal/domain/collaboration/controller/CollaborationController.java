package com.picturejournal.domain.collaboration.controller;

import com.picturejournal.domain.auth.service.AuthService;
import com.picturejournal.domain.collaboration.dto.internal.CreateFolderCommand;
import com.picturejournal.domain.collaboration.dto.internal.CreateInviteCommand;
import com.picturejournal.domain.collaboration.dto.internal.UpdateFolderCommand;
import com.picturejournal.domain.collaboration.dto.request.CreateFolderRequest;
import com.picturejournal.domain.collaboration.dto.request.CreateInviteRequest;
import com.picturejournal.domain.collaboration.dto.request.UpdateFolderRequest;
import com.picturejournal.domain.collaboration.dto.response.FolderResponse;
import com.picturejournal.domain.collaboration.dto.response.InviteResponse;
import com.picturejournal.domain.collaboration.dto.response.MemberResponse;
import com.picturejournal.domain.collaboration.service.CollaborationService;
import com.picturejournal.domain.collaboration.vo.FolderType;
import com.picturejournal.global.dto.response.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공유 폴더, 초대, 멤버 관리 API를 제공한다.
 */
@RestController
@RequestMapping("/api/v1")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class CollaborationController {

    private final CollaborationService collaborationService;
    private final AuthService authService;

    public CollaborationController(CollaborationService collaborationService, AuthService authService) {
        this.collaborationService = collaborationService;
        this.authService = authService;
    }

    /**
     * 폴더와 소유자 멤버십을 함께 생성한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 생성된 폴더와 요청자의 역할 정보
     */
    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public FolderResponse createFolder(HttpServletRequest request, @RequestBody CreateFolderRequest requestBody) {
        return FolderResponse.from(collaborationService.createFolder(resolveActorId(request),
                new CreateFolderCommand(requestBody.type(), requestBody.name(), requestBody.description())));
    }

    /**
     * 사용자가 참여 중인 폴더를 역할 정보와 함께 조회한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param type 조회할 폴더 기능 유형이며 null이면 전체 유형
     * @return 사용자가 참여 중인 폴더 목록
     */
    @GetMapping("/folders")
    @SecurityRequirement(name = "bearerAuth")
    public List<FolderResponse> listFolders(HttpServletRequest request, @RequestParam(value = "type", required = false) FolderType type) {
        return collaborationService.listFolders(resolveActorId(request), type).stream()
                .map(FolderResponse::from)
                .toList();
    }

    /**
     * 사용자의 멤버십을 확인한 뒤 폴더를 조회한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @return 접근 가능한 폴더와 사용자 역할
     */
    @GetMapping("/folders/{folderId}")
    @SecurityRequirement(name = "bearerAuth")
    public FolderResponse getFolder(HttpServletRequest request, @PathVariable UUID folderId) {
        return FolderResponse.from(collaborationService.getFolder(resolveActorId(request), folderId));
    }

    /**
     * 쓰기 권한을 확인하고 폴더 이름과 설명을 변경한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 변경 후 폴더 정보
     */
    @PatchMapping("/folders/{folderId}")
    @SecurityRequirement(name = "bearerAuth")
    public FolderResponse updateFolder(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestBody UpdateFolderRequest requestBody) {
        return FolderResponse.from(collaborationService.updateFolder(
                resolveActorId(request),
                folderId,
                new UpdateFolderCommand(requestBody.name(), requestBody.description())));
    }

    /**
     * 소유자 권한을 확인하고 편집자 또는 조회자 초대를 생성한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @param requestBody 역직렬화된 API 요청 본문
     * @return 생성된 대기 상태 초대
     */
    @PostMapping("/folders/{folderId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public InviteResponse createInvite(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestBody CreateInviteRequest requestBody) {
        return InviteResponse.from(collaborationService.createInvite(
                resolveActorId(request),
                folderId,
                new CreateInviteCommand(requestBody.role())));
    }

    @GetMapping("/invites/{token}")
    public InviteResponse getInvite(@PathVariable String token) {
        return InviteResponse.from(collaborationService.getPendingInvite(token));
    }

    /**
     * 초대 상태와 중복 멤버십을 확인한 뒤 새 멤버를 추가하고 초대를 완료 처리한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param token 조회하거나 수락할 인증·초대 토큰
     * @return 수락 완료 상태의 초대
     */
    @PostMapping("/invites/{token}/accept")
    @SecurityRequirement(name = "bearerAuth")
    public InviteResponse acceptInvite(HttpServletRequest request, @PathVariable String token) {
        return InviteResponse.from(collaborationService.acceptInvite(resolveActorId(request), token));
    }

    /**
     * 폴더 접근 권한을 확인한 뒤 전체 멤버와 역할을 반환한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @param folderId 대상 공유 폴더 식별자
     * @return 폴더의 멤버와 역할 목록
     */
    @GetMapping("/folders/{folderId}/members")
    @SecurityRequirement(name = "bearerAuth")
    public List<MemberResponse> listMembers(HttpServletRequest request, @PathVariable UUID folderId) {
        return collaborationService.listMembers(resolveActorId(request), folderId).stream()
                .map(MemberResponse::from)
                .toList();
    }

    private UUID resolveActorId(HttpServletRequest request) {
        return authService.getCurrentUser(request.getHeader("Authorization")).userId();
    }
}
