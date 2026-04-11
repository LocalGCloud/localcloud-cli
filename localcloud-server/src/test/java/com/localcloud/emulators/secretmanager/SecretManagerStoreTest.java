package com.localcloud.emulators.secretmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SecretManagerStore static parsing helpers.
 * These tests exercise pure logic only and require no database.
 */
class SecretManagerStoreTest {

    // --- parseSecretName ---

    @Test
    void parseSecretName_validFormat() {
        String[] result = SecretManagerStore.parseSecretName("projects/my-project/secrets/my-secret");
        assertEquals(2, result.length);
        assertEquals("my-project", result[0]);
        assertEquals("my-secret", result[1]);
    }

    @Test
    void parseSecretName_projectAndSecretWithNumbers() {
        String[] result = SecretManagerStore.parseSecretName("projects/proj123/secrets/secret456");
        assertEquals("proj123", result[0]);
        assertEquals("secret456", result[1]);
    }

    @Test
    void parseSecretName_tooFewSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseSecretName("projects/my-project"));
    }

    @Test
    void parseSecretName_tooManySegments() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseSecretName("projects/p/secrets/s/extra/segment"));
    }

    @Test
    void parseSecretName_wrongPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseSecretName("orgs/my-org/secrets/my-secret"));
    }

    @Test
    void parseSecretName_wrongMiddleSegment() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseSecretName("projects/p/keys/k"));
    }

    @Test
    void parseSecretName_emptyString() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseSecretName(""));
    }

    // --- parseVersionName ---

    @Test
    void parseVersionName_validFormat() {
        String[] result = SecretManagerStore.parseVersionName(
                "projects/p/secrets/s/versions/3");
        assertEquals(3, result.length);
        assertEquals("p", result[0]);
        assertEquals("s", result[1]);
        assertEquals("3", result[2]);
    }

    @Test
    void parseVersionName_latestVersion() {
        String[] result = SecretManagerStore.parseVersionName(
                "projects/p/secrets/s/versions/latest");
        assertEquals("p", result[0]);
        assertEquals("s", result[1]);
        assertEquals("latest", result[2]);
    }

    @Test
    void parseVersionName_tooFewSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseVersionName("projects/p/secrets/s"));
    }

    @Test
    void parseVersionName_wrongVersionsKeyword() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseVersionName("projects/p/secrets/s/vers/3"));
    }

    @Test
    void parseVersionName_tooManySegments() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.parseVersionName("projects/p/secrets/s/versions/3/extra"));
    }

    // --- extractProject ---

    @Test
    void extractProject_fromSecretName() {
        String project = SecretManagerStore.extractProject(
                "projects/my-project/secrets/my-secret");
        assertEquals("my-project", project);
    }

    @Test
    void extractProject_fromProjectOnly() {
        String project = SecretManagerStore.extractProject("projects/my-project");
        assertEquals("my-project", project);
    }

    @Test
    void extractProject_fromVersionName() {
        String project = SecretManagerStore.extractProject(
                "projects/p/secrets/s/versions/1");
        assertEquals("p", project);
    }

    @Test
    void extractProject_invalidPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.extractProject("orgs/my-org"));
    }

    @Test
    void extractProject_singleSegment() {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.extractProject("projects"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-slash"})
    void extractProject_malformed(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> SecretManagerStore.extractProject(input));
    }
}
