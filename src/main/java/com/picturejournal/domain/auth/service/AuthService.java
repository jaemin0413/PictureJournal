package com.picturejournal.domain.auth.service;

import com.picturejournal.domain.auth.dto.internal.AuthenticatedSession;
import com.picturejournal.domain.auth.dto.internal.LoginCommand;
import com.picturejournal.domain.auth.dto.internal.SignupCommand;
import com.picturejournal.domain.auth.entity.AuthSession;
import com.picturejournal.domain.auth.entity.UserAccount;
import com.picturejournal.domain.auth.repository.AuthSessionStore;
import com.picturejournal.domain.auth.repository.UserAccountStore;
import com.picturejournal.global.error.ErrorCode;
import com.picturejournal.global.exception.DomainException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 사용자 계정 생성과 로그인 세션 검증을 한곳에서 조정한다.
 *
 * <p>회원가입 시 이메일과 표시 이름을 정규화하고 중복 계정을 차단한 뒤,
 * PBKDF2-HMAC-SHA256으로 비밀번호를 salt와 함께 해시하여 저장한다.
 * 로그인 시에는 저장된 해시를 상수 시간 비교하고 12시간 동안 유효한 세션을 발급한다.</p>
 *
 * <p>컨트롤러는 인증 헤더의 세부 형식을 알 필요 없이 {@link #getCurrentUser(String)}만 호출한다.
 * 계정 저장은 {@link UserAccountStore}, 세션 저장은 {@link AuthSessionStore}에 위임한다.</p>
 */
@Service
public class AuthService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int PASSWORD_HASH_ITERATIONS = 65_536;
    private static final int PASSWORD_HASH_BYTES = 32;
    private static final int PASSWORD_SALT_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserAccountStore userAccountStore;
    private final AuthSessionStore authSessionStore;
    private final Clock clock;

    @Autowired
    public AuthService(UserAccountStore userAccountStore, AuthSessionStore authSessionStore) {
        this(userAccountStore, authSessionStore, Clock.systemUTC());
    }

    AuthService(UserAccountStore userAccountStore, AuthSessionStore authSessionStore, Clock clock) {
        this.userAccountStore = userAccountStore;
        this.authSessionStore = authSessionStore;
        this.clock = clock;
    }

    /**
     * 입력값을 정규화하고 중복 이메일을 확인한 뒤 비밀번호를 해시해 새 계정을 저장한다.
     *
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 저장된 사용자 계정
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public synchronized UserAccount signup(SignupCommand command) {
        String email = normalizeEmail(command.email());
        String displayName = normalizeRequired(command.displayName(), "displayName");
        String password = requirePassword(command.password());
        if (userAccountStore.findByEmail(email).isPresent()) {
            throw new DomainException(ErrorCode.CONFLICT, "Email already exists.");
        }

        Instant now = Instant.now(clock);
        UserAccount userAccount = new UserAccount(
                UUID.randomUUID(),
                email,
                displayName,
                hashPassword(password),
                now,
                now);
        return userAccountStore.save(userAccount);
    }

    /**
     * 이메일과 비밀번호를 검증하고 이후 요청에 사용할 인증 세션을 발급한다.
     *
     * @param command 서비스 유스케이스에 필요한 검증 전 입력 묶음
     * @return 발급된 세션과 인증된 사용자 계정
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public AuthenticatedSession login(LoginCommand command) {
        String email = normalizeEmail(command.email());
        String password = requirePassword(command.password());
        UserAccount userAccount = userAccountStore.findByEmail(email)
                .filter(account -> matchesPassword(account.passwordHash(), password))
                .orElseThrow(this::unauthorized);

        AuthSession authSession = authSessionStore.save(new AuthSession(UUID.randomUUID().toString(), userAccount.userId(), Instant.now(clock)));
        return new AuthenticatedSession(authSession, userAccount);
    }

    /**
     * Bearer 토큰의 형식과 만료 시간을 확인하고 연결된 사용자 계정을 반환한다.
     *
     * @param authorizationHeader Bearer 인증 토큰이 포함된 Authorization 헤더
     * @return 토큰에 연결된 현재 사용자 계정
     * @throws DomainException 입력이 유효하지 않거나 리소스·권한·상태 조건을 만족하지 못한 경우
     */
    public UserAccount getCurrentUser(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        AuthSession authSession = authSessionStore.findByToken(token)
                .filter(this::isSessionActive)
                .orElseThrow(this::unauthorized);
        return userAccountStore.findById(authSession.userId())
                .orElseThrow(this::unauthorized);
    }

    private boolean isSessionActive(AuthSession authSession) {
        Instant expiresAt = authSession.createdAt().plus(SESSION_TTL);
        return !expiresAt.isBefore(Instant.now(clock));
    }

    private DomainException unauthorized() {
        return new DomainException(ErrorCode.UNAUTHORIZED, "Authentication is required.");
    }

    private String extractBearerToken(String authorizationHeader) {
        String header = normalizeOptional(authorizationHeader);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw unauthorized();
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw unauthorized();
        }
        return token;
    }

    private String normalizeEmail(String value) {
        String normalized = normalizeRequired(value, "email");
        return normalized.toLowerCase();
    }

    private String requirePassword(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "password is required.");
        }
        return value;
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, fieldName + " is required.");
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

    private String hashPassword(String password) {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = derivePasswordHash(password.toCharArray(), salt, PASSWORD_HASH_ITERATIONS);
        // 반복 횟수와 salt를 해시 옆에 보관해야 로그인 시 동일한 조건으로 다시 계산할 수 있다.
        return PASSWORD_HASH_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    private boolean matchesPassword(String passwordHash, String candidatePassword) {
        String[] parts = passwordHash.split(":", 3);
        if (parts.length != 3) {
            return false;
        }

        int iterations;
        try {
            iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[2]);
            byte[] candidateHash = derivePasswordHash(candidatePassword.toCharArray(), salt, iterations);
            // 일반 배열 비교 대신 상수 시간 비교를 사용해 비밀번호 추측에 이용될 시간 차이를 줄인다.
            return MessageDigest.isEqual(expectedHash, candidateHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }

    }
    private byte[] derivePasswordHash(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_HASH_BYTES * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to hash password.", exception);
        }
    }
}
