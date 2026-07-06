package com.picturejournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalRuntimeContractTests {

    @Test
    void dockerPostgresDefaultsAcceptJdbcConnections() throws Exception {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/picturejournal");
        String username = System.getenv().getOrDefault("DB_USERNAME", "picturejournal");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "picturejournal-dev-password");
        String expectedDatabase = System.getenv().getOrDefault("POSTGRES_DB", databaseNameFromJdbcUrl(url));

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select 1, current_database(), current_user")) {
            resultSet.next();
            assertEquals(1, resultSet.getInt(1));
            assertEquals(expectedDatabase, resultSet.getString(2));
            assertEquals(username, resultSet.getString(3));
            assertTrue(connection.isValid(2));
        }
    }

    @Test
    void envExampleDocumentsLocalRuntimeAndStorageContract() throws Exception {
        Map<String, String> values = readEnvFile(Path.of(".env.example"));

        assertEquals("local", values.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("jdbc:postgresql://localhost:5432/picturejournal", values.get("DB_URL"));
        assertEquals("picturejournal", values.get("DB_USERNAME"));
        assertEquals("picturejournal-dev-password", values.get("DB_PASSWORD"));
        assertEquals("picturejournal", values.get("POSTGRES_DB"));
        assertEquals("picturejournal", values.get("POSTGRES_USER"));
        assertEquals("picturejournal-dev-password", values.get("POSTGRES_PASSWORD"));
        assertEquals("5432", values.get("POSTGRES_PORT"));
        assertEquals("LOCAL", values.get("STORAGE_PROVIDER"));
        assertEquals("picturejournal-local", values.get("STORAGE_BUCKET"));
        assertEquals("http://localhost:9000", values.get("STORAGE_ENDPOINT"));
        assertEquals("dev-access-key", values.get("STORAGE_ACCESS_KEY"));
        assertEquals("dev-secret-key", values.get("STORAGE_SECRET_KEY"));
        assertTrue(Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8).contains(".env"));
    }

    private String databaseNameFromJdbcUrl(String jdbcUrl) {
        String normalized = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex == normalized.length() - 1) {
            return "picturejournal";
        }

        String databasePart = normalized.substring(slashIndex + 1);
        int queryIndex = databasePart.indexOf('?');
        return queryIndex >= 0 ? databasePart.substring(0, queryIndex) : databasePart;
    }

    private Map<String, String> readEnvFile(Path path) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separator = trimmed.indexOf('=');
            values.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
        }
        return values;
    }
}
