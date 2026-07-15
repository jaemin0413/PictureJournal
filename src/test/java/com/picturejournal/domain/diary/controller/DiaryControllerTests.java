package com.picturejournal.domain.diary.controller;

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
import com.picturejournal.domain.diary.repository.FileDiaryEntryStore;
import com.picturejournal.domain.diary.service.DiaryService;
import com.picturejournal.domain.media.controller.MediaController;
import com.picturejournal.domain.media.repository.FileMediaAssetStore;
import com.picturejournal.domain.media.service.ExifMetadataExtractor;
import com.picturejournal.domain.media.service.MediaService;
import com.picturejournal.global.exception.GlobalExceptionHandler;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiaryControllerTests {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");

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
        MediaService mediaService = new MediaService(
                new FileMediaAssetStore(objectMapper, tempDir.resolve("media")),
                new ExifMetadataExtractor(objectMapper));
        com.picturejournal.domain.diary.service.DiaryService diaryService = new com.picturejournal.domain.diary.service.DiaryService(
                new com.picturejournal.domain.diary.repository.FileDiaryEntryStore(objectMapper, tempDir.resolve("diary")),
                collaborationStore,
                folderCapabilityPolicy,
                mediaService);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new CollaborationController(collaborationService, authService),
                        new MediaController(mediaService, authService),
                        new DiaryController(diaryService, authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void ownerCanUploadCreateListMapUpdateDetailAndDeleteDiaryEntry() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        String mediaId = uploadImage(ownerToken);

        MvcResult createResult = mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "First trip",
                                  "body": "Nice memory",
                                  "placeName": "Seoul Forest",
                                  "latitude": 37.5445,
                                  "longitude": 127.0374,
                                  "capturedAt": "2026-07-01T10:15:30Z",
                                  "tags": ["summer", "Summer", " park "]
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visibilityMode").value("folder_members"))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andReturn();
        String entryId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("entryId").asText();

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .param("tag", "summer")
                        .param("place", "forest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entryId").value(entryId));

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries/map", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latitude").value(37.5445));

        mockMvc.perform(patch("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Updated trip",
                                  "placeName": "Seongsu",
                                  "latitude": 37.545,
                                  "longitude": 127.04,
                                  "tags": ["edited"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated trip"))
                .andExpect(jsonPath("$.tags[0]").value("edited"));

        mockMvc.perform(get("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeName").value("Seongsu"));

        mockMvc.perform(delete("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void gpsMissingPhotoRequiresManualLocationBeforeSaving() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        String mediaId = uploadImage(ownerToken);

        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "No GPS"
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void diaryEntriesStayOutOfReelsPlaceFolders() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "REELS_PLACE");
        String mediaId = uploadImage(ownerToken);

        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Wrong folder",
                                  "latitude": 37.0,
                                  "longitude": 127.0
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void viewerCanReadButCannotWriteDiaryEntries() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String viewerToken = signupAndLogin("viewer@example.com", "Viewer");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        acceptInvite(folderId, ownerToken, viewerToken);
        String mediaId = uploadImage(ownerToken);
        String entryId = createEntry(ownerToken, folderId, mediaId);

        mockMvc.perform(get("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value(entryId));

        mockMvc.perform(patch("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(viewerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Blocked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FOLDER_WRITE_NOT_ALLOWED"));
    }

    @Test
    void directUploadRejectsNonImages() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private String createEntry(String ownerToken, String folderId, String mediaId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Readable",
                                  "latitude": 37.1,
                                  "longitude": 127.1
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("entryId").asText();
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

    private String uploadImage(String ownerToken) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TINY_PNG);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.width").value(1))
                .andExpect(jsonPath("$.height").value(1))
                .andReturn();
        return objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("mediaId").asText();
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
