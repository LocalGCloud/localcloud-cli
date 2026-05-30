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
        assertTrue(services.contains("cloudscheduler"));
        assertTrue(services.contains("cloudfunctions"));
        assertTrue(services.contains("alloydb"));
        assertTrue(services.contains("dataproc"));
        assertTrue(services.contains("cloudiam"));
        assertTrue(services.contains("spanner"));
        assertTrue(services.contains("bigtable"));
        assertTrue(services.contains("logging"));
        assertTrue(services.contains("monitoring"));
        assertTrue(services.contains("memorystore"));
        assertTrue(services.contains("workflows"));
        assertTrue(services.contains("cloudresourcemanager"));
        assertTrue(services.contains("serviceusage"));
        assertTrue(services.contains("cloudbilling"));
        assertEquals(20, services.size());
    }

    // -----------------------------------------------------------------------
    // LOCALCLOUD_ENABLE_* individual flag tests
    // -----------------------------------------------------------------------

    @Test
    void individualEnableFlagDisablesService() {
        // When LOCALCLOUD_SERVICES is not set, individual flags should apply
        setProperty("LOCALCLOUD_ENABLE_PUBSUB", "false");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertFalse(config.isServiceDynamicallyEnabled("pubsub"),
                "pubsub should be disabled when LOCALCLOUD_ENABLE_PUBSUB=false");
    }

    @Test
    void individualEnableFlagEnablesService() {
        setProperty("LOCALCLOUD_ENABLE_GKE", "true");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertTrue(config.isServiceDynamicallyEnabled("gke"),
                "gke should be enabled when LOCALCLOUD_ENABLE_GKE=true");
    }

    @Test
    void configSourceTracksEnvVsDefault() {
        setProperty("LOCALCLOUD_ENABLE_PUBSUB", "false");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertEquals("env", config.getConfigSource("pubsub"),
                "pubsub source should be 'env' when flag is explicitly set");
        assertEquals("default", config.getConfigSource("gcs"),
                "gcs source should be 'default' when no flag is set");
    }

    @Test
    void localcloudServicesOverridesIndividualFlags() {
        // LOCALCLOUD_SERVICES should win over individual LOCALCLOUD_ENABLE_* flags
        setProperty("LOCALCLOUD_SERVICES", "gcs,pubsub");
        setProperty("LOCALCLOUD_ENABLE_FIRESTORE", "true");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertFalse(config.isServiceDynamicallyEnabled("firestore"),
                "firestore should be disabled when LOCALCLOUD_SERVICES doesn't include it");
        assertEquals("env", config.getConfigSource("firestore"),
                "source should be 'env' when LOCALCLOUD_SERVICES is set");
    }

    @Test
    void mergePersistedConfigAppliesWhenNotEnvLocked() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        // GKE is disabled by default, source=default
        assertFalse(config.isServiceDynamicallyEnabled("gke"));

        // Merge persisted config that enables GKE
        config.mergePersistedConfig(java.util.Map.of("gke", true));
        assertTrue(config.isServiceDynamicallyEnabled("gke"),
                "gke should be enabled after merging persisted config");
        assertEquals("persisted", config.getConfigSource("gke"));
    }

    @Test
    void mergePersistedConfigIgnoresEnvLockedServices() {
        setProperty("LOCALCLOUD_ENABLE_GCS", "true");
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();

        // Try to merge persisted config that disables GCS
        config.mergePersistedConfig(java.util.Map.of("gcs", false));
        assertTrue(config.isServiceDynamicallyEnabled("gcs"),
                "gcs should remain enabled — env var takes precedence over persisted");
        assertEquals("env", config.getConfigSource("gcs"));
    }

    // -----------------------------------------------------------------------
    // isServiceEnabled delegates to dynamic map
    // -----------------------------------------------------------------------

    @Test
    void isServiceEnabledReflectsRuntimeToggle() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        assertTrue(config.isServiceEnabled("gcs"), "gcs should be enabled by default");

        // Runtime toggle off
        config.setServiceEnabled("gcs", false);
        assertFalse(config.isServiceEnabled("gcs"),
                "isServiceEnabled should reflect runtime toggle");
    }

    // -----------------------------------------------------------------------
    // All services present in registry
    // -----------------------------------------------------------------------

    @Test
    void serviceRegistryContainsAllServices() {
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();
        var allServices = config.getServiceRegistry().getAllServices();
        assertTrue(allServices.containsKey("gcs"));
        assertTrue(allServices.containsKey("pubsub"));
        assertTrue(allServices.containsKey("firestore"));
        assertTrue(allServices.containsKey("bigquery"));
        assertTrue(allServices.containsKey("spanner"));
        assertTrue(allServices.containsKey("bigtable"));
        assertTrue(allServices.containsKey("secretmanager"));
        assertTrue(allServices.containsKey("cloudtasks"));
        assertTrue(allServices.containsKey("cloudscheduler"));
        assertTrue(allServices.containsKey("cloudfunctions"));
        assertTrue(allServices.containsKey("alloydb"));
        assertTrue(allServices.containsKey("dataproc"));
        assertTrue(allServices.containsKey("cloudiam"));
        assertTrue(allServices.containsKey("logging"));
        assertTrue(allServices.containsKey("monitoring"));
        assertTrue(allServices.containsKey("gke"));
        assertTrue(allServices.containsKey("compute"));
        assertTrue(allServices.containsKey("cloudrun"));
        assertTrue(allServices.containsKey("memorystore"));
        assertTrue(allServices.containsKey("workflows"));
        assertTrue(allServices.containsKey("vertexai"));
        assertTrue(allServices.containsKey("kms"));
        assertTrue(allServices.containsKey("cloudsql"));
        assertTrue(allServices.containsKey("cloudresourcemanager"));
        assertTrue(allServices.containsKey("serviceusage"));
        assertTrue(allServices.containsKey("cloudbilling"));
        assertEquals(26, allServices.size(), "services.yaml should define exactly 26 services");
        assertFalse(config.isServiceEnabled("vertexai"), "vertexai should be disabled by default");
        assertFalse(config.isServiceEnabled("kms"), "kms should be disabled by default");
        assertFalse(config.isServiceEnabled("cloudsql"), "cloudsql should be disabled by default");
    }
}
