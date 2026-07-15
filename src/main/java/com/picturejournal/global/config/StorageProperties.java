package com.picturejournal.global.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 미디어와 파일 저장소에 필요한 app.storage 설정값을 바인딩한다.
 * 관련 값을 하나의 변경 불가능한 값 객체로 묶는다.
 *
 * @param provider 장소 후보를 만든 지오코딩 공급자 또는 규칙 이름
 * @param bucket 객체 저장소 버킷 이름
 * @param endpoint 객체 저장소 API 엔드포인트
 * @param accessKey 외부 객체 저장소 접근 키
 * @param secretKey 외부 객체 저장소 비밀 접근 키
 */
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
