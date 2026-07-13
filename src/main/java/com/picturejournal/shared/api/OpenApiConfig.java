package com.picturejournal.shared.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pictureJournalOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("UUID")))
                .info(new Info()
                        .title("Picture Journal API")
                        .description("Foundation API surface for the Picture Journal platform.")
                        .version("v1")
                        .license(new License().name("Proprietary")));
    }

    @Bean
    public OpenApiCustomizer strictPlaceResolutionSchemas() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (String schemaName : java.util.List.of(
                    "CandidateResolutionMode",
                    "ManualResolutionMode",
                    "ManualCoordinateResolutionMode")) {
                io.swagger.v3.oas.models.media.Schema<?> schema =
                        openApi.getComponents().getSchemas().get(schemaName);
                if (schema != null) {
                    schema.setAdditionalProperties(false);
                }
            }
        };
    }
}
