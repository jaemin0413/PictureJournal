package com.picturejournal.collaboration.api;

import com.picturejournal.auth.application.AuthService;
import com.picturejournal.collaboration.application.CollaborationService;
import com.picturejournal.collaboration.domain.FolderInviteStatus;
import com.picturejournal.folder.domain.FolderRole;
import com.picturejournal.folder.domain.FolderType;
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
public class CollaborationController {

    private final CollaborationService collaborationService;
    private final AuthService authService;

    public CollaborationController(CollaborationService collaborationService, AuthService authService) {
        this.collaborationService = collaborationService;
        this.authService = authService;
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    public FolderResponse createFolder(HttpServletRequest request, @RequestBody CreateFolderRequest requestBody) {
        return FolderResponse.from(collaborationService.createFolder(resolveActorId(request),
                new CollaborationService.CreateFolderCommand(requestBody.type(), requestBody.name(), requestBody.description())));
    }

    @GetMapping("/folders")
    @SecurityRequirement(name = "bearerAuth")
    public List<FolderResponse> listFolders(HttpServletRequest request, @RequestParam(value = "type", required = false) FolderType type) {
        return collaborationService.listFolders(resolveActorId(request), type).stream()
                .map(FolderResponse::from)
                .toList();
    }

    @GetMapping("/folders/{folderId}")
    @SecurityRequirement(name = "bearerAuth")
    public FolderResponse getFolder(HttpServletRequest request, @PathVariable UUID folderId) {
        return FolderResponse.from(collaborationService.getFolder(resolveActorId(request), folderId));
    }

    @PatchMapping("/folders/{folderId}")
    @SecurityRequirement(name = "bearerAuth")
    public FolderResponse updateFolder(
            HttpServletRequest request,
            @PathVariable UUID folderId,
            @RequestBody UpdateFolderRequest requestBody) {
        return FolderResponse.from(collaborationService.updateFolder(
                resolveActorId(request),
                folderId,
                new CollaborationService.UpdateFolderCommand(requestBody.name(), requestBody.description())));
    }

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
                new CollaborationService.CreateInviteCommand(requestBody.role())));
    }

    @GetMapping("/invites/{token}")
    public InviteResponse getInvite(@PathVariable String token) {
        return InviteResponse.from(collaborationService.getPendingInvite(token));
    }

    @PostMapping("/invites/{token}/accept")
    @SecurityRequirement(name = "bearerAuth")
    public InviteResponse acceptInvite(HttpServletRequest request, @PathVariable String token) {
        return InviteResponse.from(collaborationService.acceptInvite(resolveActorId(request), token));
    }

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

    public record CreateFolderRequest(FolderType type, String name, String description) {
    }

    public record UpdateFolderRequest(String name, String description) {
    }

    public record CreateInviteRequest(FolderRole role) {
    }

    public record FolderResponse(
            UUID folderId,
            FolderType type,
            String name,
            String description,
            FolderRole role,
            Instant createdAt,
            Instant updatedAt) {

        static FolderResponse from(CollaborationService.FolderView folderView) {
            return new FolderResponse(
                    folderView.folderId(),
                    folderView.type(),
                    folderView.name(),
                    folderView.description(),
                    folderView.role(),
                    folderView.createdAt(),
                    folderView.updatedAt());
        }
    }

    public record InviteResponse(
            UUID inviteId,
            UUID folderId,
            String token,
            FolderRole role,
            FolderInviteStatus status,
            UUID createdBy,
            UUID acceptedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant acceptedAt) {

        static InviteResponse from(CollaborationService.InviteView inviteView) {
            return new InviteResponse(
                    inviteView.inviteId(),
                    inviteView.folderId(),
                    inviteView.token(),
                    inviteView.role(),
                    inviteView.status(),
                    inviteView.createdBy(),
                    inviteView.acceptedBy(),
                    inviteView.createdAt(),
                    inviteView.updatedAt(),
                    inviteView.acceptedAt());
        }
    }

    public record MemberResponse(UUID folderId, UUID actorId, FolderRole role, Instant createdAt) {

        static MemberResponse from(CollaborationService.MemberView memberView) {
            return new MemberResponse(memberView.folderId(), memberView.actorId(), memberView.role(), memberView.createdAt());
        }
    }
}
