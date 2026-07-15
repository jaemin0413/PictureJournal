package com.picturejournal.domain.place.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.auth.controller.AuthController;
import com.picturejournal.domain.auth.repository.FileAuthSessionStore;
import com.picturejournal.domain.auth.repository.FileUserAccountStore;
import com.picturejournal.domain.auth.service.AuthService;
import com.picturejournal.domain.collaboration.controller.CollaborationController;
import com.picturejournal.domain.collaboration.entity.Folder;
import com.picturejournal.domain.collaboration.repository.FileCollaborationStore;
import com.picturejournal.domain.collaboration.service.CollaborationService;
import com.picturejournal.domain.collaboration.service.FolderCapabilityPolicyImpl;
import com.picturejournal.domain.place.repository.FilePlaceStore;
import com.picturejournal.domain.place.service.PlaceService;
import com.picturejournal.global.exception.GlobalExceptionHandler;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void singleCandidateCanBeConfirmedIntoSavedPlaceCrudFlow() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "REELS_PLACE");

        MvcResult intakeResult = mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "folderId": "%s",
                                  "rawUrl": "https://instagram.com/reel/abc",
                                  "rawTitle": "Cafe Onion",
                                  "sourceApp": "instagram",
                                  "platform": "ios",
                                  "receivedVia": "native_share"
                                }
                                """.formatted(folderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_CONFIRMATION"))
                .andExpect(jsonPath("$.candidates[0].name").value("Cafe Onion"))
                .andReturn();
        String intakeId = objectMapper.readTree(intakeResult.getResponse().getContentAsString()).get("intakeId").asText();

        MvcResult resolveResult = mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "cafe",
                                  "regionText": "Seoul",
                                  "summary": "From a reel",
                                  "keywords": ["coffee", "Coffee"],
                                  "visitStatus": "WANT_TO_GO"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intake.status").value("RESOLVED"))
                .andExpect(jsonPath("$.savedPlace.name").value("Cafe Onion"))
                .andExpect(jsonPath("$.savedPlace.keywords.length()").value(1))
                .andReturn();
        String placeId = objectMapper.readTree(resolveResult.getResponse().getContentAsString()).get("savedPlace").get("placeId").asText();

        mockMvc.perform(get("/api/v1/folders/{folderId}/saved-places", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .param("category", "cafe")
                        .param("status", "WANT_TO_GO")
                        .param("keyword", "coffee")
                        .param("region", "seo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].placeId").value(placeId));

        mockMvc.perform(patch("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Cafe Onion Anguk",
                                  "visitStatus": "VISITED",
                                  "latitude": 37.58,
                                  "longitude": 126.98
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cafe Onion Anguk"))
                .andExpect(jsonPath("$.visitStatus").value("VISITED"));

        mockMvc.perform(get("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(37.58));

        mockMvc.perform(delete("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void multipleCandidatesRequireSelectionAndResolutionIsSingleUse() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "REELS_PLACE");

        MvcResult intakeResult = mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "folderId": "%s",
                                  "rawText": "place: Alpha Bistro; place: Beta Bar",
                                  "sourceApp": "instagram",
                                  "platform": "android",
                                  "receivedVia": "native_share"
                                }
                                """.formatted(folderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_SELECTION"))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andReturn();
        JsonNode intakeJson = objectMapper.readTree(intakeResult.getResponse().getContentAsString());
        String intakeId = intakeJson.get("intakeId").asText();
        String candidateId = intakeJson.get("candidates").get(1).get("candidateId").asText();

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedPlace.name").value("Beta Bar"));

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void zeroCandidateIntakeCanBeDraftedAndManuallyFixed() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "REELS_PLACE");

        MvcResult intakeResult = mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "folderId": "%s",
                                  "rawUrl": "https://instagram.com/reel/no-place",
                                  "sourceApp": "instagram",
                                  "platform": "ios",
                                  "receivedVia": "native_share"
                                }
                                """.formatted(folderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_MANUAL_FIX"))
                .andExpect(jsonPath("$.candidates.length()").value(0))
                .andReturn();
        String intakeId = objectMapper.readTree(intakeResult.getResponse().getContentAsString()).get("intakeId").asText();

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/save-draft", intakeId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "manualName": "Manual Place",
                                  "address": "123 Road",
                                  "latitude": 35.1,
                                  "longitude": 129.1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedPlace.name").value("Manual Place"));
    }

    @Test
    void savedPlacesStayOutOfPhotoDiaryFolders() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");

        mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(ownerToken))
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void viewerCanReadButCannotResolveOrEditSavedPlaces() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String viewerToken = signupAndLogin("viewer@example.com", "Viewer");
        String folderId = createFolder(ownerToken, "REELS_PLACE");
        acceptInvite(folderId, ownerToken, viewerToken);
        String intakeId = createSingleCandidateIntake(ownerToken, folderId, "Viewer Cafe");
        String placeId = resolveSingleCandidate(ownerToken, intakeId);

        mockMvc.perform(get("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Viewer Cafe"));

        String viewerIntakeId = createSingleCandidateIntake(viewerToken, folderId, "Blocked Cafe");
        mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", viewerIntakeId)
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FOLDER_WRITE_NOT_ALLOWED"));

        mockMvc.perform(patch("/api/v1/saved-places/{placeId}", placeId)
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FOLDER_WRITE_NOT_ALLOWED"));
    }

    private String createSingleCandidateIntake(String token, String folderId, String title) throws Exception {
        MvcResult intakeResult = mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "folderId": "%s",
                                  "rawTitle": "%s",
                                  "sourceApp": "instagram",
                                  "platform": "ios",
                                  "receivedVia": "native_share"
                                }
                                """.formatted(folderId, title)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(intakeResult.getResponse().getContentAsString()).get("intakeId").asText();
    }

    private String resolveSingleCandidate(String token, String intakeId) throws Exception {
        MvcResult resolveResult = mockMvc.perform(post("/api/v1/share-intake/{intakeId}/resolve", intakeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(resolveResult.getResponse().getContentAsString()).get("savedPlace").get("placeId").asText();
    }

    private void acceptInvite(String folderId, String ownerToken, String viewerToken) throws Exception {
        MvcResult inviteResult = mockMvc.perform(post("/api/v1/folders/{folderId}/invites", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(inviteResult.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(post("/api/v1/invites/{token}/accept", token)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk());
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
