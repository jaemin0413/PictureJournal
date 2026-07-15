package com.picturejournal.domain.auth.controller;

import com.picturejournal.domain.auth.dto.internal.AuthenticatedSession;
import com.picturejournal.domain.auth.dto.internal.LoginCommand;
import com.picturejournal.domain.auth.dto.internal.SignupCommand;
import com.picturejournal.domain.auth.dto.request.LoginRequest;
import com.picturejournal.domain.auth.dto.request.SignupRequest;
import com.picturejournal.domain.auth.dto.response.LoginResponse;
import com.picturejournal.domain.auth.dto.response.UserAccountResponse;
import com.picturejournal.domain.auth.service.AuthService;
import com.picturejournal.global.dto.response.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원가입, 로그인, 현재 사용자 조회 HTTP 요청을 인증 서비스에 연결한다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 입력값을 정규화하고 중복 이메일을 확인한 뒤 비밀번호를 해시해 새 계정을 저장한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @return 저장된 사용자 계정
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserAccountResponse signup(@RequestBody SignupRequest request) {
        return UserAccountResponse.from(authService.signup(new SignupCommand(
                request.email(),
                request.displayName(),
                request.password())));
    }

    /**
     * 이메일과 비밀번호를 검증하고 이후 요청에 사용할 인증 세션을 발급한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @return 발급된 세션과 인증된 사용자 계정
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AuthenticatedSession authenticatedSession = authService.login(new LoginCommand(
                request.email(),
                request.password()));
        return LoginResponse.from(authenticatedSession);
    }

    /**
     * Authorization 헤더로 현재 로그인 사용자를 조회한다.
     *
     * @param request 인증 헤더와 HTTP 컨텍스트를 담은 서블릿 요청
     * @return 현재 인증된 사용자 응답
     */
    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public UserAccountResponse me(HttpServletRequest request) {
        return UserAccountResponse.from(authService.getCurrentUser(request.getHeader("Authorization")));
    }
}
