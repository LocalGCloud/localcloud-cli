package com.localcloud.admin;

import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Terraform env var integration via ServiceDefinition.
 * Verifies that terraformEnvVar is correctly read from ServiceDefinition
 * and produces the expected GOOGLE_*_CUSTOM_ENDPOINT output.
 */
class AdminApiServiceTerraformTest {

    private static ServiceDefinition makeDef(String id, int port, String envValuePrefix,
                                              String terraformEnvVar) {
        return new ServiceDefinition(
                id, id, port, "rest",
                "SOME_HOST", envValuePrefix, "facade",
                true, null, 0,
                Collections.emptyMap(), null, terraformEnvVar);
    }

    @Test
    void terraformEnvVar_returnsConfiguredValue() {
        var def = makeDef("gcs", 4443, "http://", "GOOGLE_STORAGE_CUSTOM_ENDPOINT");
        assertEquals("GOOGLE_STORAGE_CUSTOM_ENDPOINT", def.terraformEnvVar());
    }

    @Test
    void terraformEnvVar_nullWhenNotConfigured() {
        var def = makeDef("custom", 9999, "", null);
        assertNull(def.terraformEnvVar());
    }

    @Test
    void terraformEnvVar_emptyString() {
        var def = makeDef("custom", 9999, "", "");
        assertEquals("", def.terraformEnvVar());
    }

    @Test
    void envValue_correctEndpoint() {
        var def = makeDef("gcs", 4443, "http://", "GOOGLE_STORAGE_CUSTOM_ENDPOINT");
        assertEquals("http://localhost:4443", def.envValue("localhost"));
    }

    @Test
    void envValue_grpcEndpoint_noPrefix() {
        var def = makeDef("secretmanager", 8080, "", "GOOGLE_SECRET_MANAGER_CUSTOM_ENDPOINT");
        assertEquals("localhost:8080", def.envValue("localhost"));
    }

    @Test
    void spannerRestPort_usedForTerraform() {
        // Spanner has a REST port at 9020, gRPC at 9010
        var def = new ServiceDefinition(
                "spanner", "Spanner", 9010, "grpc",
                "SPANNER_EMULATOR_HOST", "", "external",
                true, "spanner", 9020,
                java.util.Map.of("rest", 9020), null,
                "GOOGLE_SPANNER_CUSTOM_ENDPOINT");
        assertEquals("GOOGLE_SPANNER_CUSTOM_ENDPOINT", def.terraformEnvVar());
        assertTrue(def.additionalPorts().containsKey("rest"));
        assertEquals(9020, def.additionalPorts().get("rest"));
    }

    @Test
    void allKnownServices_haveTerraformEnvVars() {
        // Load actual registry and verify all services have terraform env vars
        ServiceRegistry registry = ServiceRegistry.load(8080);
        for (var entry : registry.getAllServices().entrySet()) {
            ServiceDefinition def = entry.getValue();
            assertNotNull(def.terraformEnvVar(),
                    "Service '" + entry.getKey() + "' should have terraformEnvVar in services.yaml");
            assertTrue(def.terraformEnvVar().startsWith("GOOGLE_"),
                    "Service '" + entry.getKey() + "' terraformEnvVar should start with GOOGLE_, got: "
                            + def.terraformEnvVar());
        }
    }
}
