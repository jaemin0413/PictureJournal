package com.picturejournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class OpenApiContractTests {

    private static final Path CHECKED_IN_CONTRACT_PATH =
            Path.of("contracts", "openapi", "picture-journal.openapi.json");

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void apiDocsEndpointMatchesCheckedInOpenApiContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Picture Journal API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/folders'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/folders'].post.responses['401'].description").value("Unauthorized"))
                .andExpect(jsonPath("$.paths['/api/v1/folders'].post.responses['400'].description").value("Invalid request"))
                .andExpect(jsonPath("$.paths['/api/v1/media/direct-upload'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/folders/{folderId}/diary-entries'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/folders/{folderId}/diary-entries/map'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/share-intake'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/share-intake/{intakeId}/resolve'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/folders/{folderId}/saved-places'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/places/search'].get.responses['429'].description").value("Rate limited"))
                .andExpect(jsonPath("$.paths['/api/v1/ops/readiness'].get").exists())
                .andReturn();

        String actualContract = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode actualContractJson = objectMapper.readTree(actualContract);

        assertTrue(
                Files.exists(CHECKED_IN_CONTRACT_PATH),
                "OpenAPI contract must be checked in under contracts/openapi.");

        JsonNode expectedContractJson =
                objectMapper.readTree(Files.readString(CHECKED_IN_CONTRACT_PATH, StandardCharsets.UTF_8));

        assertEquals(expectedContractJson, actualContractJson);
    }
}
