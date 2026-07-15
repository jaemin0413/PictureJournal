package com.picturejournal.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PictureJournal API 문서의 공통 OpenAPI 메타데이터를 설정한다.
 */
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
}
