package com.picturejournal.diary.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.picturejournal.diary.application.DiaryEntryStore;
import com.picturejournal.diary.domain.DiaryEntry;
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
import com.picturejournal.media.domain.MediaAsset;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DiaryControllerTests {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");

    @TempDir
    Path tempDir;

    private AuthService authService;
    private FileCollaborationStore collaborationStore;
    private FolderCapabilityPolicyImpl folderCapabilityPolicy;
    private MediaService mediaService;
    private FileMediaAssetStore mediaAssetStore;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private MutableClock mediaClock;
    private Instant uploadedExifTakenAt;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        authService = new AuthService(
                new FileUserAccountStore(objectMapper, tempDir.resolve("users")),
                new FileAuthSessionStore(objectMapper, tempDir.resolve("sessions")));
        collaborationStore = new FileCollaborationStore(objectMapper, tempDir.resolve("collaboration"));
        folderCapabilityPolicy = new FolderCapabilityPolicyImpl(collaborationStore);
        mediaClock = new MutableClock(Instant.parse("2026-07-12T00:00:00Z"));
        CollaborationService collaborationService = new CollaborationService(collaborationStore, folderCapabilityPolicy);
        mediaAssetStore = new FileMediaAssetStore(objectMapper, tempDir.resolve("media"));
        mediaService = new MediaService(
                mediaAssetStore,
                new ExifMetadataExtractor(objectMapper) {
                    @Override
                    public ExifMetadataExtractor.ExtractedExif extract(byte[] ignoredImageBytes) {
                        return new ExifMetadataExtractor.ExtractedExif("{}", null, null, uploadedExifTakenAt, null, null);
                    }
                },
                collaborationStore,
                mediaClock);
        com.picturejournal.diary.application.DiaryService diaryService = new com.picturejournal.diary.application.DiaryService(
                new com.picturejournal.diary.application.FileDiaryEntryStore(objectMapper, tempDir.resolve("diary")),
                collaborationStore,
                folderCapabilityPolicy,
                mediaService,
                Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), java.time.ZoneOffset.UTC));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new CollaborationController(collaborationService, authService),
                        new MediaController(mediaService, authService),
                        new DiaryController(diaryService, authService))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(objectMapper))
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
    void diaryCreationUsesRequestExifOrFixedClockTimestampAndEnforcesCoordinateBoundaries() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        uploadedExifTakenAt = Instant.parse("2026-07-02T03:04:05Z");
        String requestedMediaId = uploadImage(ownerToken, folderId);
        uploadedExifTakenAt = null;

        MvcResult requestedResult = mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Requested time",
                                  "latitude": -90.0,
                                  "longitude": -180.0,
                                  "capturedAt": "2026-07-01T10:15:30Z"
                                }
                                """.formatted(requestedMediaId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capturedAt").value("2026-07-01T10:15:30Z"))
                .andReturn();
        String entryId = objectMapper.readTree(requestedResult.getResponse().getContentAsString()).get("entryId").asText();

        uploadedExifTakenAt = Instant.parse("2026-07-03T04:05:06Z");
        String exifMediaId = uploadImage(ownerToken, folderId);
        uploadedExifTakenAt = null;
        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Exif time",
                                  "latitude": 90.0,
                                  "longitude": 180.0
                                }
                                """.formatted(exifMediaId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capturedAt").value("2026-07-03T04:05:06Z"));

        String clockMediaId = uploadImage(ownerToken, folderId);
        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Clock time",
                                  "latitude": 90.0,
                                  "longitude": 180.0
                                }
                                """.formatted(clockMediaId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capturedAt").value("2026-07-12T00:00:00Z"));

        mockMvc.perform(patch("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":91.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(patch("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longitude\":-181.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(patch("/api/v1/diary-entries/{entryId}", entryId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(37.5))
                .andExpect(jsonPath("$.longitude").value(-180.0));
    }

    @Test
    void temporalFiltersExcludeEntriesBeforeAndAfterTheirInclusiveBoundaries() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        String folderId = createFolder(ownerToken, "PHOTO_DIARY");
        createEntryAt(ownerToken, folderId, uploadImage(ownerToken, folderId), "2026-06-30T23:59:59Z");
        createEntryAt(ownerToken, folderId, uploadImage(ownerToken, folderId), "2026-07-01T00:00:00Z");
        createEntryAt(ownerToken, folderId, uploadImage(ownerToken, folderId), "2026-07-02T00:00:00Z");
        createEntryAt(ownerToken, folderId, uploadImage(ownerToken, folderId), "2026-07-02T00:00:01Z");

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-07-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .param("from", "2026-07-02T00:00:00Z")
                        .param("to", "2026-07-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
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
        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Only latitude",
                                  "latitude": 37.0
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
        mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Rejected",
                                  "latitude": 37.0,
                                  "longitude": 127.0
                                }
                                """.formatted(java.util.UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(get("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        Path diaryDirectory = tempDir.resolve("diary");
        if (Files.exists(diaryDirectory)) {
            try (var files = Files.list(diaryDirectory)) {
                org.assertj.core.api.Assertions.assertThat(files.count()).isZero();
            }
        }
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
        expireMedia();

        mockMvc.perform(get("/api/v1/media/{mediaId}/binary", expiredPendingMediaId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());

        org.junit.jupiter.api.Assertions.assertEquals(1, mediaService.cleanupExpiredPending());

        String committedMediaId = uploadImage(ownerToken, folderId);
        createEntry(ownerToken, folderId, committedMediaId);
        mediaClock.setInstant(Instant.parse("2026-07-16T00:00:00Z"));

        org.junit.jupiter.api.Assertions.assertEquals(0, mediaService.cleanupExpiredPending());
        org.junit.jupiter.api.Assertions.assertEquals(
                MediaAsset.Status.COMMITTED,
                mediaAssetStore.findById(UUID.fromString(committedMediaId)).orElseThrow().status());

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

    @Test
    void diaryCommitPreservesPrimaryFailureWhenRollbackAlsoFails() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com", "Owner");
        UUID actorId = authService.getCurrentUser(bearer(ownerToken)).userId();
        UUID folderId = UUID.fromString(createFolder(ownerToken, "PHOTO_DIARY"));
        RuntimeException commitFailure = new IllegalStateException("commit failed");
        RuntimeException rollbackFailure = new IllegalStateException("rollback failed");
        MediaAsset mediaAsset = new MediaAsset(
                UUID.randomUUID(),
                actorId,
                "pending.png",
                folderId,
                "pending.png",
                "image/png",
                1,
                1,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                "0".repeat(64),
                MediaAsset.Status.PENDING,
                null,
                null,
                Instant.parse("2026-07-12T00:00:00Z"),
                Instant.parse("2026-07-13T00:00:00Z"),
                null);
        MediaService failingMediaService = new MediaService(
                new FileMediaAssetStore(objectMapper, tempDir.resolve("failing-media")),
                new ExifMetadataExtractor(objectMapper),
                collaborationStore) {
            @Override
            public MediaAsset requirePendingUpload(UUID ignoredActorId, UUID ignoredFolderId, UUID ignoredMediaId) {
                return mediaAsset;
            }

            @Override
            public MediaAsset commitDiaryMedia(MediaAsset ignoredMediaAsset, UUID ignoredFolderId, UUID ignoredEntryId) {
                throw commitFailure;
            }
        };
        DiaryEntryStore rollbackFailingStore = new DiaryEntryStore() {
            @Override
            public DiaryEntry save(DiaryEntry entry) {
                return entry;
            }

            @Override
            public Optional<DiaryEntry> findById(UUID entryId) {
                return Optional.empty();
            }

            @Override
            public List<DiaryEntry> listByFolderId(UUID ignoredFolderId) {
                return List.of();
            }

            @Override
            public void delete(UUID entryId) {
                throw rollbackFailure;
            }
        };
        com.picturejournal.diary.application.DiaryService service = new com.picturejournal.diary.application.DiaryService(
                rollbackFailingStore,
                collaborationStore,
                folderCapabilityPolicy,
                failingMediaService,
                Clock.fixed(Instant.parse("2026-07-12T00:00:00Z"), java.time.ZoneOffset.UTC));

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.createEntry(
                        actorId,
                        folderId,
                        new com.picturejournal.diary.application.DiaryService.CreateDiaryEntryCommand(
                                mediaAsset.mediaId(),
                                "Title",
                                null,
                                null,
                                37.0,
                                127.0,
                                null,
                                List.of())));

        org.junit.jupiter.api.Assertions.assertSame(commitFailure, thrown);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[] {rollbackFailure}, thrown.getSuppressed());
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
    private String createEntryAt(String ownerToken, String folderId, String mediaId, String capturedAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/folders/{folderId}/diary-entries", folderId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mediaId": "%s",
                                  "title": "Dated",
                                  "latitude": 37.1,
                                  "longitude": 127.1,
                                  "capturedAt": "%s"
                                }
                                """.formatted(mediaId, capturedAt)))
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

    private void expireMedia() {
        mediaClock.setInstant(Instant.parse("2026-07-14T00:00:00Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
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
