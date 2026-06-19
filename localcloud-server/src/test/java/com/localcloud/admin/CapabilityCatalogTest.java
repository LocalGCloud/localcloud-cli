package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CapabilityCatalogTest {

    @Mock
    private LocalCloudConfig config;

    private ServiceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = ServiceRegistry.load(8080);

        when(config.getServiceRegistry()).thenReturn(registry);
        when(config.getProjectId()).thenReturn("test-project");
        when(config.isServiceEnabled(anyString())).thenAnswer(invocation -> {
            String serviceId = invocation.getArgument(0, String.class);
            var def = registry.getService(serviceId);
            return def != null && def.defaultEnabled();
        });
    }

    @Test
    void coverageIncludesEveryRegistryService() {
        Map<String, Object> coverage = CapabilityCatalog.coverage(config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) coverage.get("services");
        assertEquals(registry.getAllServices().size(), services.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) coverage.get("summary");
        assertEquals(registry.getAllServices().size(), summary.get("total_services"));
        assertEquals("test-project", coverage.get("project_id"));
    }

    @Test
    void serviceCoverageReportsTerraformAndStateContract() {
        Map<String, Object> gcs = CapabilityCatalog.serviceCoverage(config, "gcs");

        assertNotNull(gcs);
        assertEquals("gcs", gcs.get("service_id"));
        assertEquals("partial", gcs.get("coverage_status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> terraform = (Map<String, Object>) gcs.get("terraform_resources");
        assertEquals("supported", terraform.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) gcs.get("state");
        assertEquals("available", state.get("seed"));
        assertEquals("available", state.get("reset"));
    }

    @Test
    void compatibilityWarningsExposeSqlMetadata() {
        List<Map<String, Object>> warnings = CapabilityCatalog.warnings(config, "bigquery", "sql");

        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(row -> "TABLESAMPLE".equals(row.get("keyword"))));
    }

    @Test
    void unknownServiceCoverageReturnsNull() {
        assertNull(CapabilityCatalog.serviceCoverage(config, "not-a-service"));
    }

    @Test
    void profilesOnlyIncludeKnownServices() {
        Map<String, Object> response = CapabilityCatalog.profiles(config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) response.get("profiles");

        assertFalse(profiles.isEmpty());
        for (Map<String, Object> profile : profiles) {
            @SuppressWarnings("unchecked")
            List<String> services = (List<String>) profile.get("services");
            assertTrue(services.stream().allMatch(service -> registry.getService(service) != null));
        }
    }

    @Test
    void capabilitiesExposeAllRoadmapPhases() {
        Map<String, Object> response = CapabilityCatalog.capabilities(config);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phases = (List<Map<String, Object>>) response.get("phases");
        assertEquals(7, phases.size());
        assertEquals("phase-0-truth-lifecycle", phases.get(0).get("id"));
    }
}
