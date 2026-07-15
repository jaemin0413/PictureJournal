package com.picturejournal.domain.collaboration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.domain.auth.controller.AuthController;
import com.picturejournal.domain.auth.repository.FileAuthSessionStore;
import com.picturejournal.domain.auth.repository.FileUserAccountStore;
import com.picturejournal.domain.auth.service.AuthService;
import com.picturejournal.domain.collaboration.repository.FileCollaborationStore;
import com.picturejournal.domain.collaboration.service.CollaborationService;
import com.picturejournal.domain.collaboration.service.FolderCapabilityPolicyImpl;
import com.picturejournal.global.exception.GlobalExceptionHandler;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollaborationControllerTests {

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
        CollaborationService collaborationService = new CollaborationService(
                collaborationStore,
                new FolderCapabilityPolicyImpl(collaborationStore));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new CollaborationController(collaborationService, authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createListAndFilterFoldersWithAuth() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");

        mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "PHOTO_DIARY",
                                  "name": "Diary folder",
                                  "description": "Trips"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("PHOTO_DIARY"))
                .andExpect(jsonPath("$.role").value("OWNER"));

        mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "REELS_PLACE",
                                  "name": "Places folder",
                                  "description": "Wish list"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REELS_PLACE"));

        mockMvc.perform(get("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .param("type", "PHOTO_DIARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PHOTO_DIARY"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidFolderTypeUsesSharedErrorEnvelope() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");

        mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "NOT_A_TYPE",
                                  "name": "Diary folder"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Request is invalid."));

        mockMvc.perform(get("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void inviteAcceptAndMembersFlowWorks() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String viewerToken = signupAndLogin("viewer@example.com", "Viewer");

        MvcResult folderResult = mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "PHOTO_DIARY",
                                  "name": "Diary folder",
                                  "description": "Trips"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode folderJson = objectMapper.readTree(folderResult.getResponse().getContentAsString());
        String folderId = folderJson.get("folderId").asText();

        MvcResult inviteResult = mockMvc.perform(post("/api/v1/folders/{folderId}/invites", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String token = objectMapper.readTree(inviteResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/v1/invites/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token));

        mockMvc.perform(post("/api/v1/invites/{token}/accept", token)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(get("/api/v1/folders/{folderId}/members", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void inviteCannotGrantOwnerRole() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");

        MvcResult folderResult = mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "PHOTO_DIARY",
                                  "name": "Diary folder",
                                  "description": "Trips"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String folderId = objectMapper.readTree(folderResult.getResponse().getContentAsString()).get("folderId").asText();

        mockMvc.perform(post("/api/v1/folders/{folderId}/invites", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void viewerCannotPatchFolder() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String viewerToken = signupAndLogin("viewer@example.com", "Viewer");

        MvcResult folderResult = mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "PHOTO_DIARY",
                                  "name": "Diary folder",
                                  "description": "Trips"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String folderId = objectMapper.readTree(folderResult.getResponse().getContentAsString()).get("folderId").asText();

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

        mockMvc.perform(patch("/api/v1/folders/{folderId}", folderId)
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FOLDER_WRITE_NOT_ALLOWED"));
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
