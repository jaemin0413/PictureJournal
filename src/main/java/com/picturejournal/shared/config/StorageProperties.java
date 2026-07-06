package com.picturejournal.shared.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @NotNull StorageProvider provider,
        String bucket,
        String endpoint,
        String accessKey,
        String secretKey) {

    @AssertTrue(message = "S3_COMPATIBLE storage requires bucket, endpoint, access-key, and secret-key.")
    public boolean isProviderConfigurationValid() {
        if (provider != StorageProvider.S3_COMPATIBLE) {
            return true;
        }
        return hasText(bucket) && hasText(endpoint) && hasText(accessKey) && hasText(secretKey);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
