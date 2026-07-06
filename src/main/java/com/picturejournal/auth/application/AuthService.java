package com.picturejournal.auth.application;

import com.picturejournal.auth.domain.UserAccount;
import com.picturejournal.shared.error.DomainException;
import com.picturejournal.shared.error.ErrorCode;
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

    public AuthenticatedSession login(LoginCommand command) {
        String email = normalizeEmail(command.email());
        String password = requirePassword(command.password());
        UserAccount userAccount = userAccountStore.findByEmail(email)
                .filter(account -> matchesPassword(account.passwordHash(), password))
                .orElseThrow(this::unauthorized);

        AuthSession authSession = authSessionStore.save(new AuthSession(UUID.randomUUID().toString(), userAccount.userId(), Instant.now(clock)));
        return new AuthenticatedSession(authSession, userAccount);
    }

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

    public record SignupCommand(String email, String displayName, String password) {
    }

    public record LoginCommand(String email, String password) {
    }

    public record AuthenticatedSession(AuthSession authSession, UserAccount userAccount) {
    }
}
