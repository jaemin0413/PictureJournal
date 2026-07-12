package com.picturejournal.ops.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.auth.api.AuthController;
import com.picturejournal.auth.application.AuthService;
import com.picturejournal.auth.application.FileAuthSessionStore;
import com.picturejournal.auth.application.FileUserAccountStore;
import com.picturejournal.collaboration.api.CollaborationController;
import com.picturejournal.collaboration.application.CollaborationService;
import com.picturejournal.collaboration.application.FileCollaborationStore;
import com.picturejournal.collaboration.application.FolderCapabilityPolicyImpl;
import com.picturejournal.ops.application.GeocodeService;
import com.picturejournal.ops.application.OperationsReadinessService;
import com.picturejournal.place.api.PlaceController;
import com.picturejournal.place.application.FilePlaceStore;
import com.picturejournal.place.application.PlaceService;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperationsControllerTests {

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
        FilePlaceStore placeStore = new FilePlaceStore(objectMapper, tempDir.resolve("places"));
        PlaceService placeService = new PlaceService(placeStore, collaborationStore, folderCapabilityPolicy);
        GeocodeService geocodeService = new GeocodeService();
        OperationsReadinessService readinessService = new OperationsReadinessService(placeStore, geocodeService);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new CollaborationController(collaborationService, authService),
                        new PlaceController(placeService, authService),
                        new OperationsController(geocodeService, readinessService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void geocodeSearchAndReverseUseCacheBeforeThrottle() throws Exception {
        mockMvc.perform(get("/api/v1/places/search").param("q", "Seoul Forest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.candidates[0].name").value("Seoul Forest"));

        mockMvc.perform(get("/api/v1/places/search").param("q", "Seoul Forest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true));

        mockMvc.perform(get("/api/v1/geocode/reverse").param("lat", "37.1").param("lng", "127.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        mockMvc.perform(get("/api/v1/geocode/reverse").param("lat", "37.1").param("lng", "127.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true));
    }

    @Test
    void geocodeMissesAreRateLimitedWithBackoffSignal() throws Exception {
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(get("/api/v1/places/search").param("q", "miss-" + index))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/places/search").param("q", "miss-over-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void readinessReportsMissingEnvAndUnresolvedDrafts() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "REELS_PLACE");
        mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "folderId": "%s",
                                  "clientIntakeId": "ops-readiness-intake",
                                  "fingerprint": "ops-readiness-no-place",
                                  "rawUrl": "https://instagram.com/reel/no-place",
                                  "sourceApp": "instagram",
                                  "platform": "ios",
                                  "receivedVia": "native_share"
                                }
                                """.formatted(folderId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/ops/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHomeServerEnvKeys.length()").value(8))
                .andExpect(jsonPath("$.unresolvedShareIntakeCount").value(1));
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
