package com.picturejournal.auth.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.auth.application.AuthSession;
import com.picturejournal.auth.application.AuthService;
import com.picturejournal.auth.application.FileAuthSessionStore;
import com.picturejournal.auth.application.FileUserAccountStore;
import com.picturejournal.auth.domain.UserAccount;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTests {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private FileAuthSessionStore authSessionStore;
    private FileUserAccountStore userAccountStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        authSessionStore = new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions"));
        userAccountStore = new FileUserAccountStore(objectMapper, tempDir.resolve("users"));
        AuthService authService = new AuthService(
                userAccountStore,
                authSessionStore);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void signupLoginAndMeFlowWorks() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "displayName": "Owner",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value("Owner"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.get("token").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.displayName").value("Owner"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void logoutRevokesTokenForMeValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "displayName": "Owner",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void staleRestoredSessionIsRejectedByMeValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "displayName": "Owner",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.get("token").asText();
        UUID userId = UUID.fromString(loginJson.get("user").get("userId").asText());
        authSessionStore.save(new AuthSession(token, userId, Instant.EPOCH));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void loginFailuresHaveIdenticalGenericUnauthorizedResponses() throws Exception {
        String signupBody = """
                {
                  "email": "owner@example.com",
                  "displayName": "Owner",
                  "password": "secret"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        MvcResult wrongPassword = login("owner@example.com", "wrong");
        MvcResult unknownEmail = login("unknown@example.com", "secret");
        UserAccount storedUser = userAccountStore.findByEmail("owner@example.com").orElseThrow();
        userAccountStore.save(new UserAccount(
                storedUser.userId(),
                storedUser.email(),
                storedUser.displayName(),
                "invalid:stored:hash",
                storedUser.createdAt(),
                storedUser.updatedAt()));
        MvcResult malformedStoredHash = login("owner@example.com", "secret");

        assertIdenticalUnauthorizedResponses(List.of(wrongPassword, unknownEmail, malformedStoredHash));
    }
    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isUnauthorized())
                .andReturn();
    }

    private void assertIdenticalUnauthorizedResponses(List<MvcResult> results) throws Exception {
        for (MvcResult result : results) {
            assertEquals(401, result.getResponse().getStatus());
            JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals("UNAUTHORIZED", response.path("code").asText());
            assertEquals("Authentication is required.", response.path("message").asText());
            java.util.Set<String> fields = new java.util.HashSet<>();
            response.fieldNames().forEachRemaining(fields::add);
            assertEquals(java.util.Set.of("timestamp", "status", "error", "code", "message", "details"), fields);
            assertEquals(401, response.path("status").asInt());
            assertEquals("Unauthorized", response.path("error").asText());
            assertTrue(response.path("details").isObject());
            assertEquals(0, response.path("details").size());
            String responseBody = result.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
            assertFalse(responseBody.contains("password"));
            assertFalse(responseBody.contains("hash"));
            assertFalse(responseBody.contains("cause"));
            assertFalse(responseBody.contains("secret"));
            assertFalse(responseBody.contains("missing@example.com"));
        }
    }
    @Test
    void malformedAuthorizationHeadersAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Basic credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedAuthEndpointsRejectMissingAndMalformedCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Basic credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void blankSignupCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "   ",
                                  "displayName": "Owner",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "password": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
