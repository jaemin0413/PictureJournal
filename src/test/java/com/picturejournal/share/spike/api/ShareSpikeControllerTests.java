package com.picturejournal.share.spike.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturejournal.share.spike.application.FileShareSpikeDraftStore;
import com.picturejournal.share.spike.application.ShareSpikeService;
import com.picturejournal.shared.error.GlobalExceptionHandler;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ShareSpikeControllerTests {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ShareSpikeService shareSpikeService = new ShareSpikeService(new FileShareSpikeDraftStore(objectMapper, tempDir));
        mockMvc = MockMvcBuilders.standaloneSetup(new ShareSpikeController(shareSpikeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createAndFetchDraftExposeJsonContract() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/share-spike/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceApp": "instagram",
                                  "platform": "IOS",
                                  "rawUrl": "https://instagram.com/reel/123",
                                  "rawTitle": "Trip reel",
                                  "rawText": "caption"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_AUTH"))
                .andExpect(jsonPath("$.sourceApp").value("instagram"))
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String draftId = created.get("draftId").asText();

        mockMvc.perform(get("/api/v1/share-spike/drafts/{draftId}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftId").value(draftId))
                .andExpect(jsonPath("$.platform").value("IOS"))
                .andExpect(jsonPath("$.rawUrl").value("https://instagram.com/reel/123"));
    }

    @Test
    void bindAuthAndFolderSelectionAdvanceContinuityState() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/share-spike/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceApp": "instagram",
                                  "platform": "ANDROID",
                                  "rawTitle": "Shared title"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String draftId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("draftId").asText();
        UUID actorId = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/share-spike/drafts/{draftId}/bind-auth", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"" + actorId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_FOLDER_SELECTION"))
                .andExpect(jsonPath("$.actorId").value(actorId.toString()));

        mockMvc.perform(post("/api/v1/share-spike/drafts/{draftId}/folder-selection", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + folderId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))
                .andExpect(jsonPath("$.folderId").value(folderId.toString()));
    }

    @Test
    void missingDraftUsesSharedErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/share-spike/drafts/{draftId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void invalidFolderSelectionWithoutActorUsesSharedErrorEnvelope() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/share-spike/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceApp": "instagram",
                                  "platform": "ANDROID",
                                  "rawText": "payload"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String draftId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("draftId").asText();
        UUID folderId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/share-spike/drafts/{draftId}/folder-selection", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"" + folderId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("folder selection requires an authenticated actor."));
    }
}
