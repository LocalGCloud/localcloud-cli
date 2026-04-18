package com.localcloud.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LocalCloudConfig}.
 *
 * <p>Since {@code fromEnvironment()} reads {@code System.getenv()} first and falls
 * back to {@code System.getProperty()} (with the key lowercased and underscores
 * replaced by dots), we use system properties to inject test values without
 * needing to modify the actual environment.
 */
class LocalCloudConfigTest {

    /**
     * Track every system property we set so we can reliably clear them after each test.
     */
    private final List<String> propsToClean = new ArrayList<>();

    @BeforeEach
    void clearTestProperties() {
        propsToClean.clear();
    }

    @AfterEach
    void removeTestProperties() {
        for (String key : propsToClean) {
            System.clearProperty(key);
        }
    }

    private void setProperty(String envName, String value) {
        // LocalCloudConfig.env() converts ENV_VAR_NAME -> env.var.name for property lookup
        String propKey = envName.toLowerCase().replace('_', '.');
        System.setProperty(propKey, value);
        propsToClean.add(propKey);
    }

    // -----------------------------------------------------------------------
    // Default values (no env vars / system properties set)
    // -----------------------------------------------------------------------

    @Test
    void defaultProjectId() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("local-project", config.getProjectId());
    }

    @Test
    void defaultGatewayPort() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(8080, config.getGatewayPort());
    }

    @Test
    void defaultIamMode() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("permissive", config.getIamMode());
    }

    @Test
    void defaultPersistenceEnabled() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertTrue(config.isPersistenceEnabled());
    }

    @Test
    void defaultLogVerbosity() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("info", config.getLogVerbosity());
    }

    @Test
    void defaultIamPolicyFileIsEmpty() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("", config.getIamPolicyFile());
    }

    // -----------------------------------------------------------------------
    // PostgreSQL defaults
    // -----------------------------------------------------------------------

    @Test
    void defaultPostgresHost() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("localhost", config.getPostgresHost());
    }

    @Test
    void defaultPostgresPort() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(5432, config.getPostgresPort());
    }

    @Test
    void defaultPostgresDatabase() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("localcloud", config.getPostgresDatabase());
    }

    @Test
    void defaultPostgresUser() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("localcloud", config.getPostgresUser());
    }

    @Test
    void defaultPostgresPassword() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("localcloud", config.getPostgresPassword());
    }

    // -----------------------------------------------------------------------
    // Service list parsing
    // -----------------------------------------------------------------------

    @Test
    void serviceListParsedWithWhitespaceTrimming() {
        setProperty("LOCALCLOUD_SERVICES", "gcs, pubsub");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(List.of("gcs", "pubsub"), config.getEnabledServices());
    }

    @Test
    void serviceListFiltersEmptyEntries() {
        setProperty("LOCALCLOUD_SERVICES", "gcs,,pubsub");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(List.of("gcs", "pubsub"), config.getEnabledServices());
    }

    @Test
    void serviceListWithLeadingAndTrailingWhitespace() {
        setProperty("LOCALCLOUD_SERVICES", " gcs , pubsub , firestore ");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(List.of("gcs", "pubsub", "firestore"), config.getEnabledServices());
    }

    @Test
    void singleServiceParsesCorrectly() {
        setProperty("LOCALCLOUD_SERVICES", "gcs");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(List.of("gcs"), config.getEnabledServices());
    }

    // -----------------------------------------------------------------------
    // isServiceEnabled
    // -----------------------------------------------------------------------

    @Test
    void isServiceEnabledReturnsTrueForEnabledService() {
        setProperty("LOCALCLOUD_SERVICES", "gcs,pubsub");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertTrue(config.isServiceEnabled("gcs"));
        assertTrue(config.isServiceEnabled("pubsub"));
    }

    @Test
    void isServiceEnabledReturnsFalseForDisabledService() {
        setProperty("LOCALCLOUD_SERVICES", "gcs,pubsub");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertFalse(config.isServiceEnabled("firestore"));
        assertFalse(config.isServiceEnabled("bigquery"));
    }

    @Test
    void isServiceEnabledIsCaseSensitive() {
        setProperty("LOCALCLOUD_SERVICES", "gcs");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertTrue(config.isServiceEnabled("gcs"));
        assertFalse(config.isServiceEnabled("GCS"));
    }

    // -----------------------------------------------------------------------
    // Custom values via system properties
    // -----------------------------------------------------------------------

    @Test
    void customProjectId() {
        setProperty("LOCALCLOUD_PROJECT", "my-test-project");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("my-test-project", config.getProjectId());
    }

    @Test
    void customGatewayPort() {
        setProperty("LOCALCLOUD_PORT", "9090");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(9090, config.getGatewayPort());
    }

    @Test
    void invalidPortFallsBackToDefault() {
        setProperty("LOCALCLOUD_PORT", "not-a-number");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals(8080, config.getGatewayPort());
    }

    @Test
    void persistenceCanBeDisabled() {
        setProperty("LOCALCLOUD_PERSISTENCE", "false");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertFalse(config.isPersistenceEnabled());
    }

    @Test
    void customIamMode() {
        setProperty("LOCALCLOUD_IAM_MODE", "strict");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("strict", config.getIamMode());
    }

    @Test
    void customPostgresSettings() {
        setProperty("LOCALCLOUD_PG_HOST", "db.example.com");
        setProperty("LOCALCLOUD_PG_PORT", "5433");
        setProperty("LOCALCLOUD_PG_DATABASE", "mydb");
        setProperty("LOCALCLOUD_PG_USER", "admin");
        setProperty("LOCALCLOUD_PG_PASSWORD", "secret");

        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();

        assertEquals("db.example.com", config.getPostgresHost());
        assertEquals(5433, config.getPostgresPort());
        assertEquals("mydb", config.getPostgresDatabase());
        assertEquals("admin", config.getPostgresUser());
        assertEquals("secret", config.getPostgresPassword());
    }

    @Test
    void defaultEnabledServicesContainsAllExpected() {
        // When no LOCALCLOUD_SERVICES is set, default-enabled services should be present
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        List<String> services = config.getEnabledServices();
        assertTrue(services.contains("gcs"));
        assertTrue(services.contains("pubsub"));
        assertTrue(services.contains("firestore"));
        assertTrue(services.contains("bigquery"));
        assertTrue(services.contains("secretmanager"));
        assertTrue(services.contains("cloudtasks"));
        assertTrue(services.contains("spanner"));
        assertTrue(services.contains("bigtable"));
        assertTrue(services.contains("logging"));
        assertTrue(services.contains("monitoring"));
        assertTrue(services.contains("memorystore"));
        assertTrue(services.contains("workflows"));
        assertEquals(12, services.size());
    }
}
