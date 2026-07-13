package com.picturejournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
                .andExpect(jsonPath("$.paths['/api/v1/folders/{folderId}/diary-entries'].post.responses['201'].content['*/*'].schema.$ref").value("#/components/schemas/DiaryEntryResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/folders/{folderId}/diary-entries'].get.responses['200'].content['*/*'].schema.items.$ref").value("#/components/schemas/DiaryEntryResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/folders/{folderId}/diary-entries/map'].get.responses['200'].content['*/*'].schema.items.$ref").value("#/components/schemas/DiaryMapEntryResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/diary-entries/{entryId}'].get.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/DiaryEntryResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/diary-entries/{entryId}'].patch.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/DiaryEntryResponse"))
                .andExpect(jsonPath("$.components.schemas.UpsertDiaryEntryRequest.properties.latitude.minimum").value(-90.0))
                .andExpect(jsonPath("$.components.schemas.UpsertDiaryEntryRequest.properties.latitude.maximum").value(90.0))
                .andExpect(jsonPath("$.components.schemas.UpsertDiaryEntryRequest.properties.longitude.minimum").value(-180.0))
                .andExpect(jsonPath("$.components.schemas.UpsertDiaryEntryRequest.properties.longitude.maximum").value(180.0))
                .andExpect(jsonPath("$.paths['/api/v1/folders/{folderId}/saved-places'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses['201'].content['*/*'].schema.$ref").value("#/components/schemas/UserAccountResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/LoginResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/UserAccountResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/places/search'].get.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/SearchResult"))
                .andExpect(jsonPath("$.paths['/api/v1/geocode/reverse'].get.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/ReverseResult"))
                .andExpect(jsonPath("$.paths['/api/v1/ops/readiness'].get.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/ReadinessReport"))
                .andExpect(jsonPath("$.paths['/api/v1/share-intake/{intakeId}'].patch.requestBody.content.application/json.schema.$ref").value("#/components/schemas/UpdateShareIntakeRequest"))
                .andExpect(jsonPath("$.paths['/api/v1/share-intake/{intakeId}'].get.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/ShareIntakeResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/share-intake/{intakeId}'].patch.responses['200'].content['*/*'].schema.$ref").value("#/components/schemas/ShareIntakeResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/places/search'].get.responses['429'].description").value("Rate limited"))
                .andExpect(jsonPath("$.paths['/api/v1/ops/readiness'].get").exists())
                .andReturn();

        String actualContract = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode actualContractJson = objectMapper.readTree(actualContract);
        Set<String> updateProperties = new HashSet<>();
        actualContractJson.path("components").path("schemas").path("UpdateShareIntakeRequest").path("properties")
                .fieldNames().forEachRemaining(updateProperties::add);
        assertEquals(Set.of("rawUrl", "rawTitle", "rawText"), updateProperties);
        JsonNode createShareSchema = actualContractJson.path("components").path("schemas").path("CreateShareIntakeRequest");
        assertEquals(3, createShareSchema.path("anyOf").size());
        Set<String> createRequired = new HashSet<>();
        createShareSchema.path("required").forEach(value -> createRequired.add(value.asText()));
        assertEquals(
                Set.of("folderId", "clientIntakeId", "sourceApp", "platform", "receivedVia", "contentFingerprint"),
                createRequired);
        JsonNode resolveSchema = actualContractJson.path("components").path("schemas").path("ResolveShareIntakeRequest");
        assertEquals(3, resolveSchema.path("oneOf").size());
        assertEquals(3, actualContractJson.path("components").path("schemas")
                .path("ManualCoordinateResolutionMode").path("required").size());
        for (String modeSchema : Set.of(
                "CandidateResolutionMode",
                "ManualResolutionMode",
                "ManualCoordinateResolutionMode")) {
            assertEquals(
                    false,
                    actualContractJson.path("components").path("schemas").path(modeSchema)
                            .path("additionalProperties").asBoolean());
        }

        assertTrue(
                Files.exists(CHECKED_IN_CONTRACT_PATH),
                "OpenAPI contract must be checked in under contracts/openapi.");

        JsonNode expectedContractJson =
                objectMapper.readTree(Files.readString(CHECKED_IN_CONTRACT_PATH, StandardCharsets.UTF_8));

        assertEquals(expectedContractJson, actualContractJson);
    }

    @Test
    void productionJsonBindingRejectsLegacyFingerprintOnOtherwiseValidShareIntake() throws Exception {
        String email = "contract-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Contract User","password":"secret"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).path("token").asText();

        MvcResult folder = mockMvc.perform(post("/api/v1/folders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"REELS_PLACE","name":"Contract Places"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String folderId = objectMapper.readTree(folder.getResponse().getContentAsString()).path("folderId").asText();

        mockMvc.perform(post("/api/v1/share-intake")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "folderId":"%s",
                                  "clientIntakeId":"contract-legacy-key",
                                  "rawTitle":"Contract Cafe",
                                  "sourceApp":"contract-test",
                                  "platform":"web",
                                  "receivedVia":"manual",
                                  "contentFingerprint":"%s",
                                  "fingerprint":"%s"
                                }
                                """.formatted(folderId, "a".repeat(64), "b".repeat(64))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Request is invalid."));
    }
}
