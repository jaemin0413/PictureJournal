package com.picturejournal;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;

/**
 * PictureJournal 백엔드의 Spring Boot 진입점이다.
 */
@ConfigurationPropertiesScan
@SpringBootApplication
public class PictureJournalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PictureJournalApplication.class, args);
    }
}
