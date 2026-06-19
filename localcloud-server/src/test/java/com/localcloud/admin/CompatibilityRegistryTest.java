package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import com.localcloud.config.ServiceRegistry;

import org.junit.jupiter.api.Test;

class CompatibilityRegistryTest {

    @Test
    void registryCoversEveryServiceAndValidates() {
        ServiceRegistry services = ServiceRegistry.load(8080);

        CompatibilityRegistry registry = assertDoesNotThrow(() -> CompatibilityRegistry.load(services));
        Map<String, Object> asMap = registry.asMap(services);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) asMap.get("services");
        assertEquals(services.getAllServices().size(), rows.size());
        assertEquals(CompatibilityRegistry.SCHEMA_VERSION, asMap.get("schema_version"));
    }

    @Test
    void supportedClaimsHaveEvidence() {
        ServiceRegistry services = ServiceRegistry.load(8080);
        CompatibilityRegistry registry = CompatibilityRegistry.load(services);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) registry.asMap(services).get("services");
        for (Map<String, Object> service : rows) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> operations = (List<Map<String, Object>>) service.get("operations");
            for (Map<String, Object> op : operations) {
                if ("supported".equals(op.get("status"))) {
                    @SuppressWarnings("unchecked")
                    List<String> evidence = (List<String>) op.get("evidence");
                    assertFalse(evidence.isEmpty(), service.get("service_id") + ":" + op.get("id"));
                }
            }
        }
    }

    @Test
    void warningsCanBeFilteredForBigQuerySql() {
        ServiceRegistry services = ServiceRegistry.load(8080);
        CompatibilityRegistry registry = CompatibilityRegistry.load(services);

        List<Map<String, Object>> warnings = registry.warnings("bigquery", "sql");

        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(row -> "TABLESAMPLE".equals(row.get("keyword"))));
        assertTrue(warnings.stream().allMatch(row -> "bigquery".equals(row.get("service_id"))));
    }

    @Test
    void schemaResourceIsAvailable() {
        String schema = CompatibilityRegistry.schemaJson();

        assertNotNull(schema);
        assertTrue(schema.contains("unsupportedOperation"));
    }

    @Test
    void evidenceSourceFilesExistWhenTheyAreRepoPaths() {
        ServiceRegistry services = ServiceRegistry.load(8080);
        CompatibilityRegistry registry = CompatibilityRegistry.load(services);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) registry.evidenceSummary().get("evidence");
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        for (Map<String, Object> row : evidence) {
            String source = String.valueOf(row.get("source"));
            if (source == null || source.isBlank() || source.startsWith("http")) {
                continue;
            }
            assertTrue(Files.exists(repoRoot.resolve(source)),
                    "Missing evidence source for " + row.get("id") + ": " + source);
        }
    }
}
