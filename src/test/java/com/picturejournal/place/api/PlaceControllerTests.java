package com.picturejournal.place.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
                        .content(shareJson(folderId, "client-happy", "place: Cafe Onion")))
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
        String inviteToken = objectMapper.readTree(mockMvc.perform(post("/api/v1/folders/{folderId}/invites", folderA)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(post("/api/v1/invites/{token}/accept", inviteToken)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk());

        JsonNode ownerFolderA = postShare(ownerToken, shareJson(folderA, "same-client-id", "A Cafe"));
        JsonNode ownerFolderB = postShare(ownerToken, shareJson(folderB, "same-client-id", "B Cafe"));
        JsonNode otherActorFolderA = postShare(otherToken, shareJson(folderA, "same-client-id", "Other Cafe"));
        org.assertj.core.api.Assertions.assertThat(ownerFolderA.get("intakeId").asText())
                .isNotEqualTo(ownerFolderB.get("intakeId").asText())
                .isNotEqualTo(otherActorFolderA.get("intakeId").asText());
        org.assertj.core.api.Assertions.assertThat(ownerFolderA.get("resolvedPlaceId").asText())
                .isNotEqualTo(otherActorFolderA.get("resolvedPlaceId").asText());
        org.assertj.core.api.Assertions.assertThat(ownerFolderB.get("intakeId").asText())
                .isNotEqualTo(otherActorFolderA.get("intakeId").asText());
        org.assertj.core.api.Assertions.assertThat(ownerFolderB.get("resolvedPlaceId").asText())
                .isNotEqualTo(otherActorFolderA.get("resolvedPlaceId").asText());

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderA)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.containsInAnyOrder("A Cafe", "Other Cafe")));
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
        String originalCandidateId = unresolved.get("candidates").get(0).get("candidateId").asText();

        mockMvc.perform(get("/api/v1/share-intake/{intakeId}", intakeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_MANUAL_FIX"));

        MvcResult repairedResult = mockMvc.perform(patch("/api/v1/share-intake/{intakeId}", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawTitle\":\"place: Repaired Cafe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawTitle").value("place: Repaired Cafe"))
                .andReturn();
        JsonNode repaired = objectMapper.readTree(repairedResult.getResponse().getContentAsString());
        String candidateId = repaired.get("candidates").get(0).get("candidateId").asText();
        org.assertj.core.api.Assertions.assertThat(candidateId).isNotEqualTo(originalCandidateId);

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\",\"category\":\"cafe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.status").value("RESOLVED"))
                .andExpect(jsonPath("$.savedPlace.category").value("cafe"));
    }

    @Test
    void savedPlacePatchPersistsAfterReloadAndIntakeLinkedPlaceCannotBeDeleted() throws Exception {
        String token = signupAndLogin("saved-place@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        JsonNode intake = postShare(token, shareJson(folderId, "client-saved-place", "Original Cafe"));
        String intakeId = intake.get("intakeId").asText();
        String placeId = intake.get("resolvedPlaceId").asText();

        mockMvc.perform(patch("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Cafe\",\"category\":\"cafe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Cafe"));

        mockMvc.perform(get("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Cafe"))
                .andExpect(jsonPath("$.category").value("cafe"));

        mockMvc.perform(delete("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/share-intake/{intakeId}", intakeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedPlaceId").value(placeId))
                .andExpect(jsonPath("$.resolvedPlace.name").value("Updated Cafe"));
    }

    @Test
    void viewerCannotRepairShareIntake() throws Exception {
        String ownerToken = signupAndLogin("repair-owner@example.com", "Owner");
        String viewerToken = signupAndLogin("repair-viewer@example.com", "Viewer");
        String folderId = createFolder(ownerToken, "REELS_PLACE");
        JsonNode unresolved = postShare(ownerToken, shareJson(folderId, "viewer-repair", "place: Alpha; place: Beta"));
        String intakeId = unresolved.get("intakeId").asText();
        String candidateId = unresolved.get("candidates").get(0).get("candidateId").asText();
        String inviteToken = objectMapper.readTree(mockMvc.perform(post("/api/v1/folders/{folderId}/invites", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/v1/invites/{token}/accept", inviteToken)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk());

        JsonNode intakeBeforePatch = getJson(ownerToken, "/api/v1/share-intake/" + intakeId);
        JsonNode placesBeforePatch = getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places");
        mockMvc.perform(patch("/api/v1/share-intake/{intakeId}", intakeId)
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawTitle\":\"Viewer mutation\"}"))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/share-intake/" + intakeId))
                .isEqualTo(intakeBeforePatch);
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places"))
                .isEqualTo(placesBeforePatch);

        JsonNode intakeBeforeResolve = getJson(ownerToken, "/api/v1/share-intake/" + intakeId);
        JsonNode placesBeforeResolve = getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places");
        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/share-intake/" + intakeId))
                .isEqualTo(intakeBeforeResolve);
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places"))
                .isEqualTo(placesBeforeResolve);
    }
    @Test
    void boundShareIntakeCannotResolveIntoAnotherFolder() throws Exception {
        String token = signupAndLogin("cross-folder@example.com", "Owner");
        String sourceFolderId = createFolder(token, "REELS_PLACE");
        String destinationFolderId = createFolder(token, "REELS_PLACE");
        String intakeId = postShare(token, shareJson(sourceFolderId, "cross-folder", "place: Alpha; place: Beta"))
                .get("intakeId").asText();

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + destinationFolderId + "\",\"manualName\":\"Alpha\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test
    void resolvedShareIntakeRejectsDifferentResolution() throws Exception {
        String token = signupAndLogin("terminal-resolution@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        JsonNode intake = postShare(token, shareJson(folderId, "terminal-resolution", "place: Alpha; place: Beta"));
        String intakeId = intake.get("intakeId").asText();
        String firstCandidateId = intake.get("candidates").get(0).get("candidateId").asText();
        String secondCandidateId = intake.get("candidates").get(1).get("candidateId").asText();

        MvcResult firstResolution = mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + firstCandidateId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resolved = objectMapper.readTree(firstResolution.getResponse().getContentAsString());
        String resolvedPlaceId = resolved.get("savedPlace").get("placeId").asText();
        JsonNode intakeBeforeConflict = getJson(token, "/api/v1/share-intake/" + intakeId);
        JsonNode placesBeforeConflict = getJson(token, "/api/v1/folders/" + folderId + "/saved-places");

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + secondCandidateId + "\"}"))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(getJson(token, "/api/v1/share-intake/" + intakeId))
                .isEqualTo(intakeBeforeConflict);
        org.assertj.core.api.Assertions.assertThat(getJson(token, "/api/v1/folders/" + folderId + "/saved-places"))
                .isEqualTo(placesBeforeConflict);
        org.assertj.core.api.Assertions.assertThat(intakeBeforeConflict.get("resolvedPlaceId").asText())
                .isEqualTo(resolvedPlaceId);
    }

    @Test
    void shareIntakeRejectsBlankClientIntakeId() throws Exception {
        String token = signupAndLogin("blank-client-id@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");

        mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(folderId, "   ", "Cafe")))
                .andExpect(status().isBadRequest());
    }
    @Test
    void shareIntakeRejectsMissingClientIntakeId() throws Exception {
        String token = signupAndLogin("missing-client-id@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");

        mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "folderId": "%s",
                                  "rawTitle": "Cafe",
                                  "sourceApp": "instagram",
                                  "platform": "ios",
                                  "receivedVia": "native_share"
                                }
                                """.formatted(folderId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolvedShareIntakeCannotBePatched() throws Exception {
        String token = signupAndLogin("resolved-update@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        String intakeId = postShare(token, shareJson(folderId, "resolved-update", "Cafe")).get("intakeId").asText();

        mockMvc.perform(patch("/api/v1/share-intake/{intakeId}", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawTitle\":\"Changed Cafe\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void savedPlaceFiltersAndUpdateErrorsAreObservable() throws Exception {
        String token = signupAndLogin("place-filters@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        String placeId = postShare(token, shareJson(folderId, "filter-place", "Filter Cafe")).get("resolvedPlaceId").asText();
        String categoryMismatchId = postShare(token, shareJson(folderId, "filter-category", "Bakery")).get("resolvedPlaceId").asText();
        String statusMismatchId = postShare(token, shareJson(folderId, "filter-status", "Planned Cafe")).get("resolvedPlaceId").asText();
        String keywordMismatchId = postShare(token, shareJson(folderId, "filter-keyword", "Noisy Cafe")).get("resolvedPlaceId").asText();
        String regionMismatchId = postShare(token, shareJson(folderId, "filter-region", "Busan Cafe")).get("resolvedPlaceId").asText();

        updatePlace(token, placeId, "cafe", "Seoul", "brunch", "quiet", "VISITED");
        updatePlace(token, categoryMismatchId, "bakery", "Seoul", "brunch", "quiet", "VISITED");
        updatePlace(token, statusMismatchId, "cafe", "Seoul", "brunch", "quiet", "WANT_TO_GO");
        updatePlace(token, keywordMismatchId, "cafe", "Seoul", "brunch", "noisy", "VISITED");
        updatePlace(token, regionMismatchId, "cafe", "Busan", "brunch", "quiet", "VISITED");

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token))
                        .queryParam("category", "cafe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].placeId").value(org.hamcrest.Matchers.containsInAnyOrder(
                        placeId, statusMismatchId, keywordMismatchId, regionMismatchId)));
        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token))
                        .queryParam("status", "VISITED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].placeId").value(org.hamcrest.Matchers.containsInAnyOrder(
                        placeId, categoryMismatchId, keywordMismatchId, regionMismatchId)));
        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token))
                        .queryParam("keyword", "quiet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].placeId").value(org.hamcrest.Matchers.containsInAnyOrder(
                        placeId, categoryMismatchId, statusMismatchId, regionMismatchId)));
        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token))
                        .queryParam("region", "Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].placeId").value(org.hamcrest.Matchers.containsInAnyOrder(
                        placeId, categoryMismatchId, statusMismatchId, keywordMismatchId)));
        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token))
                        .queryParam("category", "cafe")
                        .queryParam("status", "VISITED")
                        .queryParam("keyword", "quiet")
                        .queryParam("region", "Seoul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].placeId").value(placeId));

        JsonNode placesBeforeMissingPatch = getJson(token, "/api/v1/folders/" + folderId + "/saved-places");
        mockMvc.perform(patch("/api/v1/saved-places/{placeId}", UUID.randomUUID())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Missing Cafe\"}"))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(getJson(token, "/api/v1/folders/" + folderId + "/saved-places"))
                .isEqualTo(placesBeforeMissingPatch);

        JsonNode placesBeforeBlankPatch = getJson(token, "/api/v1/folders/" + folderId + "/saved-places");
        mockMvc.perform(patch("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(getJson(token, "/api/v1/folders/" + folderId + "/saved-places"))
                .isEqualTo(placesBeforeBlankPatch);
    }

    @Test
    void viewerCannotUpdateOrDeleteSavedPlace() throws Exception {
        String ownerToken = signupAndLogin("place-owner@example.com", "Owner");
        String viewerToken = signupAndLogin("place-viewer@example.com", "Viewer");
        String folderId = createFolder(ownerToken, "REELS_PLACE");

        String intakeId = postShare(ownerToken, shareJson(folderId, "viewer-place", "Viewer Cafe")).get("intakeId").asText();
        String placeId = getJson(ownerToken, "/api/v1/share-intake/" + intakeId).get("resolvedPlaceId").asText();
        String inviteToken = objectMapper.readTree(mockMvc.perform(post("/api/v1/folders/{folderId}/invites", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(post("/api/v1/invites/{token}/accept", inviteToken)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk());

        JsonNode placeBeforePatch = getJson(ownerToken, "/api/v1/saved-places/" + placeId);
        JsonNode intakeBeforePatch = getJson(ownerToken, "/api/v1/share-intake/" + intakeId);
        JsonNode placesBeforePatch = getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places");
        mockMvc.perform(patch("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Viewer mutation\"}"))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/saved-places/" + placeId))
                .isEqualTo(placeBeforePatch);
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/share-intake/" + intakeId))
                .isEqualTo(intakeBeforePatch);
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places"))
                .isEqualTo(placesBeforePatch);

        JsonNode placeBeforeDelete = getJson(ownerToken, "/api/v1/saved-places/" + placeId);
        JsonNode intakeBeforeDelete = getJson(ownerToken, "/api/v1/share-intake/" + intakeId);
        JsonNode placesBeforeDelete = getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places");
        mockMvc.perform(delete("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/saved-places/" + placeId))
                .isEqualTo(placeBeforeDelete);
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/share-intake/" + intakeId))
                .isEqualTo(intakeBeforeDelete);
        org.assertj.core.api.Assertions.assertThat(getJson(ownerToken, "/api/v1/folders/" + folderId + "/saved-places"))
                .isEqualTo(placesBeforeDelete);
    }

    @Test
    void resolvedShareIntakeResolutionIsIdempotentForSamePlace() throws Exception {
        String token = signupAndLogin("same-resolution@example.com", "Owner");
        String folderId = createFolder(token, "REELS_PLACE");
        JsonNode intake = postShare(token, shareJson(folderId, "same-resolution", "place: Alpha; place: Beta"));
        String intakeId = intake.get("intakeId").asText();
        String candidateId = intake.get("candidates").get(0).get("candidateId").asText();

        MvcResult firstResolution = mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode first = objectMapper.readTree(firstResolution.getResponse().getContentAsString());
        String resolvedPlaceId = first.get("savedPlace").get("placeId").asText();
        String resolvedIntakeId = first.get("intake").get("intakeId").asText();

        MvcResult replayResolution = mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.status").value("RESOLVED"))
                .andReturn();
        JsonNode replay = objectMapper.readTree(replayResolution.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(replay.get("intake").get("intakeId").asText()).isEqualTo(resolvedIntakeId);
        org.assertj.core.api.Assertions.assertThat(replay.get("savedPlace").get("placeId").asText()).isEqualTo(resolvedPlaceId);
        org.assertj.core.api.Assertions.assertThat(replay.get("intake").get("resolvedPlaceId").asText()).isEqualTo(resolvedPlaceId);

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].placeId").value(resolvedPlaceId));
    }
    private void updatePlace(
            String token,
            String placeId,
            String category,
            String regionText,
            String firstKeyword,
            String secondKeyword,
            String visitStatus) throws Exception {
        mockMvc.perform(patch("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"%s","regionText":"%s","keywords":["%s","%s"],"visitStatus":"%s"}
                                """.formatted(category, regionText, firstKeyword, secondKeyword, visitStatus)))
                .andExpect(status().isOk());
    }
    private JsonNode getJson(String token, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
        String trustedTitle = title.regionMatches(true, 0, "place:", 0, "place:".length())
                ? title
                : "place: " + title;
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
                """.formatted(folderId, clientIntakeId, trustedTitle, fingerprintForTest(clientIntakeId + "|" + trustedTitle));
    }

    private String shareJson(String folderId, String clientIntakeId, String title, String fingerprint) {
        String trustedTitle = title.regionMatches(true, 0, "place:", 0, "place:".length())
                ? title
                : "place: " + title;
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
                """.formatted(folderId, clientIntakeId, trustedTitle, fingerprintForTest(fingerprint));
    }

    private String fingerprintForTest(String value) {
        if (value.matches("[0-9a-fA-F]{64}")) {
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
