package com.picturejournal.place.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.auth.api.AuthController;
import com.picturejournal.auth.application.AuthService;
import com.picturejournal.auth.application.FileAuthSessionStore;
import com.picturejournal.auth.application.FileUserAccountStore;
import com.picturejournal.collaboration.api.CollaborationController;
import com.picturejournal.collaboration.application.CollaborationService;
import com.picturejournal.collaboration.application.FileCollaborationStore;
import com.picturejournal.collaboration.application.FolderCapabilityPolicyImpl;
import com.picturejournal.place.application.FilePlaceStore;
import com.picturejournal.place.application.PlaceService;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlaceControllerTests {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        AuthService authService = new AuthService(
                new FileUserAccountStore(objectMapper, tempDir.resolve("users")),
                new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions")));
        FileCollaborationStore collaborationStore = new FileCollaborationStore(objectMapper, tempDir.resolve("collaboration"));
        FolderCapabilityPolicyImpl folderCapabilityPolicy = new FolderCapabilityPolicyImpl(collaborationStore);
        CollaborationService collaborationService = new CollaborationService(collaborationStore, folderCapabilityPolicy);
        PlaceService placeService = new PlaceService(
                new FilePlaceStore(objectMapper, tempDir.resolve("places")),
                collaborationStore,
                folderCapabilityPolicy);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new CollaborationController(collaborationService, authService),
                        new PlaceController(placeService, authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void trustedSingleCandidateAutoSavesWithoutConfirmation() throws Exception {
        String token = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");

        mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(folderId, "client-happy", "Cafe Onion")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientIntakeId").value("client-happy"))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedPlace.name").value("Cafe Onion"))
                .andExpect(jsonPath("$.resolvedPlaceId").isNotEmpty())
                .andExpect(jsonPath("$.candidates.length()").value(1));

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Cafe Onion"));
    }

    @Test
    void uncertainIntakeBecomesUnresolved() throws Exception {
        String token = signupAndLogin("uncertain@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");

        MvcResult intakeResult = mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(folderId, "client-uncertain", "place: Alpha; place: Beta")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_MANUAL_FIX"))
                .andExpect(jsonPath("$.failureReason").isNotEmpty())
                .andExpect(jsonPath("$.resolvedPlaceId").isEmpty())
                .andReturn();
        String intakeId = objectMapper.readTree(intakeResult.getResponse().getContentAsString()).get("intakeId").asText();

        mockMvc.perform(get("/api/v1/folders/{folderId}/share-intake/unresolved", folderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].intakeId").value(intakeId));
    }

    @Test
    void duplicateRetryReturnsExistingTerminalResult() throws Exception {
        String token = signupAndLogin("retry@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        String payload = shareJson(folderId, "client-retry", "Retry Cafe");

        JsonNode first = postShare(token, payload);
        JsonNode second = postShare(token, payload);

        org.assertj.core.api.Assertions.assertThat(second.get("intakeId").asText()).isEqualTo(first.get("intakeId").asText());
        org.assertj.core.api.Assertions.assertThat(second.get("resolvedPlace").get("placeId").asText())
                .isEqualTo(first.get("resolvedPlace").get("placeId").asText());

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void clientIntakeIdIsScopedByUserAndFolder() throws Exception {
        String ownerToken = signupAndLogin("scope-owner@example.com", "Owner");
        String otherToken = signupAndLogin("scope-other@example.com", "Other");
        String folderA = createFolder(ownerToken, "REELS_PLACE");
        String folderB = createFolder(ownerToken, "REELS_PLACE");
        String otherFolder = createFolder(otherToken, "REELS_PLACE");

        postShare(ownerToken, shareJson(folderA, "same-client-id", "A Cafe"));
        postShare(ownerToken, shareJson(folderB, "same-client-id", "B Cafe"));
        postShare(otherToken, shareJson(otherFolder, "same-client-id", "Other Cafe"));

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderA)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("A Cafe"));
        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderB)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("B Cafe"));
    }

    @Test
    void sameFingerprintInSameFolderDedupeAcrossDifferentClientIds() throws Exception {
        String token = signupAndLogin("fingerprint@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");

        JsonNode first = postShare(token, shareJson(folderId, "client-fingerprint-a", "Fingerprint Cafe", "fp-same"));
        JsonNode second = postShare(token, shareJson(folderId, "client-fingerprint-b", "Renamed Fingerprint Cafe", "fp-same"));

        org.assertj.core.api.Assertions.assertThat(second.get("intakeId").asText()).isEqualTo(first.get("intakeId").asText());

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void sameFingerprintIsNotDedupedAcrossFoldersOrUsers() throws Exception {
        String ownerToken = signupAndLogin("fp-owner@example.com", "Owner");
        String otherToken = signupAndLogin("fp-other@example.com", "Other");
        String folderA = createFolder(ownerToken, "REELS_PLACE");
        String folderB = createFolder(ownerToken, "REELS_PLACE");
        String otherFolder = createFolder(otherToken, "REELS_PLACE");

        JsonNode first = postShare(ownerToken, shareJson(folderA, "fp-a", "A Cafe", "fp-cross"));
        JsonNode second = postShare(ownerToken, shareJson(folderB, "fp-b", "B Cafe", "fp-cross"));
        JsonNode third = postShare(otherToken, shareJson(otherFolder, "fp-c", "C Cafe", "fp-cross"));

        org.assertj.core.api.Assertions.assertThat(List.of(second.get("intakeId").asText(), third.get("intakeId").asText()))
                .doesNotContain(first.get("intakeId").asText());
    }

    @Test
    void concurrentSameIntakeRequestsReturnOneSavedResolvedResult() throws Exception {
        String token = signupAndLogin("concurrent@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        String payload = shareJson(folderId, "client-concurrent", "Concurrent Cafe", "fp-concurrent");
        Callable<JsonNode> request = () -> postShare(token, payload);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<JsonNode> results = executor.invokeAll(List.of(request, request)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            org.assertj.core.api.Assertions.assertThat(results.get(1).get("intakeId").asText())
                    .isEqualTo(results.get(0).get("intakeId").asText());
            org.assertj.core.api.Assertions.assertThat(results)
                    .allSatisfy(result -> {
                        org.assertj.core.api.Assertions.assertThat(result.get("status").asText()).isEqualTo("RESOLVED");
                        org.assertj.core.api.Assertions.assertThat(result.get("failureReason").isNull()).isTrue();
                        org.assertj.core.api.Assertions.assertThat(result.get("resolvedPlaceId").isNull()).isFalse();
                    });
        }

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/folders/{folderId}/share-intake/unresolved", folderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
    @Test
    void unresolvedIntakeCanBeUpdatedAndResolvedLater() throws Exception {
        String token = signupAndLogin("repair@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        JsonNode unresolved = postShare(token, shareJson(folderId, "client-repair", "place: Alpha; place: Beta"));
        String intakeId = unresolved.get("intakeId").asText();
        String candidateId = unresolved.get("candidates").get(0).get("candidateId").asText();

        mockMvc.perform(get("/api/v1/share-intake/{intakeId}", intakeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_MANUAL_FIX"));

        mockMvc.perform(patch("/api/v1/share-intake/{intakeId}", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(folderId, "client-repair", "Repaired Cafe")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawTitle").value("Repaired Cafe"));

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\",\"category\":\"cafe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.status").value("RESOLVED"))
                .andExpect(jsonPath("$.savedPlace.category").value("cafe"));
    }

    private JsonNode postShare(String token, String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String shareJson(String folderId, String clientIntakeId, String title) {
        return """
                {
                  "folderId": "%s",
                  "clientIntakeId": "%s",
                  "rawUrl": "https://instagram.com/reel/example",
                  "rawTitle": "%s",
                  "sourceApp": "instagram",
                  "platform": "ios",
                  "receivedVia": "native_share"
                }
                """.formatted(folderId, clientIntakeId, title);
    }

    private String shareJson(String folderId, String clientIntakeId, String title, String fingerprint) {
        return """
                {
                  "folderId": "%s",
                  "clientIntakeId": "%s",
                  "rawUrl": "https://instagram.com/reel/example",
                  "rawTitle": "%s",
                  "sourceApp": "instagram",
                  "platform": "ios",
                  "receivedVia": "native_share",
                  "contentFingerprint": "%s"
                }
                """.formatted(folderId, clientIntakeId, title, fingerprint);
    }

    private String createFolder(String ownerToken, String type) throws Exception {
        MvcResult folderResult = mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "%s",
                                  "name": "Folder"
                                }
                                """.formatted(type)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(folderResult.getResponse().getContentAsString()).get("folderId").asText();
    }

    private String signupAndLogin(String email, String displayName) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"" + displayName + "\",\"password\":\"secret\"}"))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
