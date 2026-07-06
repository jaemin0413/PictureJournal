package com.picturejournal.auth.api;

import com.picturejournal.auth.application.AuthService;
import com.picturejournal.auth.domain.UserAccount;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
})
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserAccountResponse signup(@RequestBody SignupRequest request) {
        return UserAccountResponse.from(authService.signup(new AuthService.SignupCommand(
                request.email(),
                request.displayName(),
                request.password())));
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AuthService.AuthenticatedSession authenticatedSession = authService.login(new AuthService.LoginCommand(
                request.email(),
                request.password()));
        return LoginResponse.from(authenticatedSession);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public UserAccountResponse me(HttpServletRequest request) {
        return UserAccountResponse.from(authService.getCurrentUser(request.getHeader("Authorization")));
    }

    public record SignupRequest(String email, String displayName, String password) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String token, UserAccountResponse user) {

        static LoginResponse from(AuthService.AuthenticatedSession authenticatedSession) {
            return new LoginResponse(
                    authenticatedSession.authSession().token(),
                    UserAccountResponse.from(authenticatedSession.userAccount()));
        }
    }

    public record UserAccountResponse(
            UUID userId,
            String email,
            String displayName,
            Instant createdAt,
            Instant updatedAt) {

        static UserAccountResponse from(UserAccount userAccount) {
            return new UserAccountResponse(
                    userAccount.userId(),
                    userAccount.email(),
                    userAccount.displayName(),
                    userAccount.createdAt(),
                    userAccount.updatedAt());
        }
    }
}
