package com.picturejournal.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ActiveProfiles("test")
@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.provider=LOCAL",
        "app.storage.bucket=picturejournal-local",
        "app.storage.endpoint=http://localhost:9000",
        "app.storage.access-key=dev-access-key",
        "app.storage.secret-key=dev-secret-key"
})
class StoragePropertiesContractTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(StoragePropertiesBindingTestConfiguration.class);

    @Autowired
    private StorageProperties storageProperties;

    @Test
    void exposesLocalDefaultsAndMinioReadyConfigurationSeam() {
        assertEquals(StorageProvider.LOCAL, storageProperties.provider());
        assertEquals("picturejournal-local", storageProperties.bucket());
        assertEquals("http://localhost:9000", storageProperties.endpoint());
    }

    @Test
    void rejectsIncompleteS3CompatibleConfiguration() {
        contextRunner
                .withPropertyValues(
                        "app.storage.provider=S3_COMPATIBLE",
                        "app.storage.bucket=picturejournal",
                        "app.storage.endpoint=",
                        "app.storage.access-key=",
                        "app.storage.secret-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "S3_COMPATIBLE storage requires bucket, endpoint, access-key, and secret-key.");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageProperties.class)
    static class StoragePropertiesBindingTestConfiguration {
    }
}
