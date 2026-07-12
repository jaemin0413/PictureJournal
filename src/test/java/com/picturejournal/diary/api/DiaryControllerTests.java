package com.picturejournal.diary.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.picturejournal.auth.api.AuthController;
import com.picturejournal.auth.application.AuthService;
import com.picturejournal.auth.application.FileAuthSessionStore;
import com.picturejournal.auth.application.FileUserAccountStore;
import com.picturejournal.collaboration.api.CollaborationController;
import com.picturejournal.collaboration.application.CollaborationService;
import com.picturejournal.collaboration.application.FileCollaborationStore;
import com.picturejournal.collaboration.application.FolderCapabilityPolicyImpl;
import com.picturejournal.media.api.MediaController;
import com.picturejournal.media.application.ExifMetadataExtractor;
import com.picturejournal.media.application.FileMediaAssetStore;
import com.picturejournal.media.application.MediaService;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import java.nio.file.Files;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
                new ExifMetadataExtractor(objectMapper),
                collaborationStore);
        com.picturejournal.diary.application.DiaryService diaryService = new com.picturejournal.diary.application.DiaryService(
                new com.picturejournal.diary.application.FileDiaryEntryStore(objectMapper, tempDir.resolve("diary")),
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
        String mediaId = uploadImage(ownerToken, folderId);

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
        String mediaId = uploadImage(ownerToken, folderId);

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

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void diaryEntriesStayOutOfReelsPlaceFolders() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "REELS_PLACE");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TINY_PNG);
        mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .param("intendedFolderId", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void viewerCanReadButCannotWriteDiaryEntries() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String viewerToken = signupAndLogin("viewer@example.com", "Viewer");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        acceptInvite(folderId, ownerToken, viewerToken);
        String mediaId = uploadImage(ownerToken, folderId);
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
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .param("intendedFolderId", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void directUploadRejectsMultipleFiles() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        MockMultipartFile first = new MockMultipartFile("file", "first.png", "image/png", TINY_PNG);
        MockMultipartFile second = new MockMultipartFile("file", "second.png", "image/png", TINY_PNG);

        mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(first)
                        .file(second)
                        .param("intendedFolderId", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void directUploadRejectsOversizedImages() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        byte[] oversized = new byte[20 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "oversized.png", "image/png", oversized);

        mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .param("intendedFolderId", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void directUploadRejectsDeclaredChecksumMismatch() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TINY_PNG);

        mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .param("intendedFolderId", folderId)
                        .param("checksumSha256", "0".repeat(64))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void diaryCommitRejectsUnauthorizedAndAlreadyCommittedMediaWithoutPlaceholders() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String otherToken = signupAndLogin("other@example.com", "Other");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        String mediaId = uploadImage(ownerToken, folderId);
        acceptInvite(folderId, ownerToken, otherToken, "EDITOR");

        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Unauthorized",
                                  "latitude": 37.0,
                                  "longitude": 127.0
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String entryId = createEntry(ownerToken, folderId, mediaId);

        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Duplicate",
                                  "latitude": 37.0,
                                  "longitude": 127.0
                                }
                                """.formatted(mediaId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].entryId").value(entryId));

        mockMvc.perform(get("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaId").value(mediaId));
    }

    @Test
    void directUploadRejectsDeclaredMimeThatDoesNotMatchImageBytes() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", TINY_PNG);

        mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .param("intendedFolderId", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void mediaBinaryRequiresAuthorizationAndDoesNotExposeStorageKey() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String viewerToken = signupAndLogin("viewer@example.com", "Viewer");
        String outsiderToken = signupAndLogin("outsider@example.com", "Outsider");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        acceptInvite(folderId, ownerToken, viewerToken);
        String mediaId = uploadImage(ownerToken, folderId);

        mockMvc.perform(get("/api/v1/media/{mediaId}/binary", mediaId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));

        mockMvc.perform(get("/api/v1/media/{mediaId}/binary", mediaId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isForbidden());

        createEntry(ownerToken, folderId, mediaId);

        mockMvc.perform(get("/api/v1/media/{mediaId}/binary", mediaId)
                        .header("Authorization", bearer(viewerToken)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(TINY_PNG));

        mockMvc.perform(get("/api/v1/media/{mediaId}/binary", mediaId)
                        .header("Authorization", bearer(outsiderToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredPendingMediaIsUnavailableAndCleanupDoesNotDeleteCommittedMedia() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        String expiredPendingMediaId = uploadImage(ownerToken, folderId);
        expireMedia(expiredPendingMediaId);

        mockMvc.perform(get("/api/v1/media/{mediaId}/binary", expiredPendingMediaId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/media/pending/cleanup")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedPendingMedia").value(1));

        String committedMediaId = uploadImage(ownerToken, folderId);
        createEntry(ownerToken, folderId, committedMediaId);
        expireMedia(committedMediaId);

        mockMvc.perform(post("/api/v1/media/pending/cleanup")
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedPendingMedia").value(0));

        mockMvc.perform(get("/api/v1/media/{mediaId}/binary", committedMediaId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(TINY_PNG));
    }

    @Test
    void diaryCommitRejectsMediaUploadedForAnotherFolder() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String intendedFolderId = createFolder(ownerToken, "PHOTO_DIARY");
        String otherFolderId = createFolder(ownerToken, "PHOTO_DIARY");
        String mediaId = uploadImage(ownerToken, intendedFolderId);

        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", otherFolderId)
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

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", otherFolderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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
        acceptInvite(folderId, ownerToken, viewerToken, "VIEWER");
    }

    private void acceptInvite(String folderId, String ownerToken, String viewerToken, String role) throws Exception {
        MvcResult inviteResult = mockMvc.perform(post("/api/v1/folders/{folderId}/invites", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
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

    private String uploadImage(String ownerToken, String intendedFolderId) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TINY_PNG);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/media/direct-upload")
                        .file(file)
                        .param("intendedFolderId", intendedFolderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.intendedFolderId").value(intendedFolderId))
                .andExpect(jsonPath("$.width").value(1))
                .andExpect(jsonPath("$.height").value(1))
                .andExpect(jsonPath("$.checksumSha256").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("mediaId").asText();
    }

    private void expireMedia(String mediaId) throws Exception {
        Path assetPath = tempDir.resolve("media").resolve("assets").resolve(mediaId + ".json");
        ObjectNode asset = (ObjectNode) objectMapper.readTree(Files.readString(assetPath));
        asset.put("pendingExpiresAt", Instant.parse("2026-07-10T00:00:00Z").toString());
        Files.writeString(assetPath, objectMapper.writeValueAsString(asset));
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
