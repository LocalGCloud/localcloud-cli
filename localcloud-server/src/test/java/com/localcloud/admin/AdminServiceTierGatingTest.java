package com.localcloud.admin;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.gateway.RequestLogger;
import com.localcloud.licensing.LicenseTier;
import com.localcloud.licensing.LicenseTierProvider;
import com.localcloud.licensing.StaticLicenseTierProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for license-tier gating in AdminApiService.
 *
 * Verifies that enableService() and updateServiceConfig() block services
 * whose minTier exceeds the current license tier.
 */
class AdminServiceTierGatingTest {

    private LocalCloudConfig config;
    private ServiceRegistry registry;
    private AdminApiService service;

    /** Build a ServiceDefinition with the given minTier. */
    private static ServiceDefinition def(String id, LicenseTier minTier) {
        return new ServiceDefinition(
                id, id, 8080, "grpc",
                "SOME_VAR", "", "facade",
                false, null, 0,
                Collections.emptyMap(), null,
                "GOOGLE_SOME_ENDPOINT", minTier);
    }

    private AdminApiService buildService(LicenseTier tier,
                                         Map<String, ServiceDefinition> defs) {
        config = mock(LocalCloudConfig.class);
        registry = mock(ServiceRegistry.class);
        when(config.getServiceRegistry()).thenReturn(registry);
        when(registry.getAllServices()).thenReturn(defs);

        // Services start as disabled so enableService() proceeds past the "already enabled" guard
        when(config.isServiceDynamicallyEnabled(anyString())).thenReturn(false);
        when(config.getConfigSource(anyString())).thenReturn("yaml");

        LicenseTierProvider tierProvider = new StaticLicenseTierProvider(tier);

        RequestLogger requestLogger = mock(RequestLogger.class);
        ProjectService projectService = mock(ProjectService.class);
        ServiceRoutingRepository routingRepository = mock(ServiceRoutingRepository.class);
        CredentialBroker credentialBroker = mock(CredentialBroker.class);
        ServiceConfigRepository serviceConfigRepository = mock(ServiceConfigRepository.class);

        return new AdminApiService(config, requestLogger, projectService,
                routingRepository, credentialBroker, serviceConfigRepository, tierProvider);
    }

    // -----------------------------------------------------------------------
    // enableService() tests
    // -----------------------------------------------------------------------

    @Test
    void enableProServiceAsCommunity_returns403() throws Exception {
        var defs = new LinkedHashMap<String, ServiceDefinition>();
        defs.put("spanner", def("spanner", LicenseTier.PRO));

        service = buildService(LicenseTier.COMMUNITY, defs);
        when(registry.getAllServices()).thenReturn(defs);

        HttpResponse response = service.enableService("spanner");
        var agg = response.aggregate().join();

        assertEquals(HttpStatus.FORBIDDEN, agg.status());
        String body = agg.contentUtf8();
        assertTrue(body.contains("pro"), "Body should mention required tier: " + body);
        assertTrue(body.contains("community"), "Body should mention current tier: " + body);
        assertTrue(body.contains("upgrade_url"), "Body should contain upgrade_url: " + body);

        // Verify service was NOT enabled
        verify(config, never()).setServiceEnabled(eq("spanner"), eq(true));
    }

    @Test
    void enableProServiceAsPro_returns200() throws Exception {
        var defs = new LinkedHashMap<String, ServiceDefinition>();
        defs.put("spanner", def("spanner", LicenseTier.PRO));

        service = buildService(LicenseTier.PRO, defs);
        when(registry.getAllServices()).thenReturn(defs);

        HttpResponse response = service.enableService("spanner");
        var agg = response.aggregate().join();

        // Should succeed (200 enabled) — not 403
        assertEquals(HttpStatus.OK, agg.status());
        String body = agg.contentUtf8();
        assertFalse(body.contains("upgrade_url"), "PRO tier should not be blocked: " + body);
        assertTrue(body.contains("enabled"), "Should report enabled: " + body);
    }

    @Test
    void enableCommunityServiceAsCommunity_returns200() throws Exception {
        var defs = new LinkedHashMap<String, ServiceDefinition>();
        defs.put("gcs", def("gcs", LicenseTier.COMMUNITY));

        service = buildService(LicenseTier.COMMUNITY, defs);
        when(registry.getAllServices()).thenReturn(defs);

        HttpResponse response = service.enableService("gcs");
        var agg = response.aggregate().join();

        assertEquals(HttpStatus.OK, agg.status());
        String body = agg.contentUtf8();
        assertFalse(body.contains("upgrade_url"), "Community service should not be blocked: " + body);
    }

    @Test
    void enableProServiceAsEnterprise_returns200() throws Exception {
        var defs = new LinkedHashMap<String, ServiceDefinition>();
        defs.put("spanner", def("spanner", LicenseTier.PRO));

        service = buildService(LicenseTier.ENTERPRISE, defs);
        when(registry.getAllServices()).thenReturn(defs);

        HttpResponse response = service.enableService("spanner");
        var agg = response.aggregate().join();

        assertEquals(HttpStatus.OK, agg.status());
    }

    @Test
    void enableUnknownService_returns404() throws Exception {
        service = buildService(LicenseTier.COMMUNITY, Collections.emptyMap());

        HttpResponse response = service.enableService("nonexistent");
        var agg = response.aggregate().join();

        assertEquals(HttpStatus.NOT_FOUND, agg.status());
    }

    // -----------------------------------------------------------------------
    // updateServiceConfig() tests
    // -----------------------------------------------------------------------

    @Test
    void updateConfigEnableProServiceAsCommunity_blocked() throws Exception {
        var defs = new LinkedHashMap<String, ServiceDefinition>();
        defs.put("spanner", def("spanner", LicenseTier.PRO));

        service = buildService(LicenseTier.COMMUNITY, defs);
        when(registry.getAllServices()).thenReturn(defs);

        // PUT /config/services with spanner=true as COMMUNITY tier
        HttpResponse response = service.updateServiceConfig("{\"spanner\": true}");
        var agg = response.aggregate().join();

        assertEquals(HttpStatus.FORBIDDEN, agg.status());
        String body = agg.contentUtf8();
        assertTrue(body.contains("blocked"), "Body should have blocked map: " + body);
        assertTrue(body.contains("spanner"), "Body should name the blocked service: " + body);
        assertTrue(body.contains("upgrade_url"), "Body should contain upgrade_url: " + body);

        // Verify service was NOT enabled
        verify(config, never()).setServiceEnabled(eq("spanner"), eq(true));
    }

    @Test
    void updateConfigDisableProServiceAsCommunity_allowed() throws Exception {
        var defs = new LinkedHashMap<String, ServiceDefinition>();
        defs.put("spanner", def("spanner", LicenseTier.PRO));

        service = buildService(LicenseTier.COMMUNITY, defs);
        when(registry.getAllServices()).thenReturn(defs);
        // Return a non-null config source so the env-lock check passes
        when(config.getConfigSource("spanner")).thenReturn("yaml");
        // Stub getServiceConfig() return values
        when(config.isServiceDynamicallyEnabled("spanner")).thenReturn(true);

        ServiceConfigRepository repo = mock(ServiceConfigRepository.class);

        // Disabling a PRO service should be allowed regardless of tier
        HttpResponse response = service.updateServiceConfig("{\"spanner\": false}");
        var agg = response.aggregate().join();

        // Should not be 403 — disabling doesn't require tier check
        assertNotEquals(HttpStatus.FORBIDDEN, agg.status());
        verify(config).setServiceEnabled("spanner", false);
    }

    @Test
    void updateConfigEnableCommunityServiceAsCommunity_succeeds() throws Exception {
        var defs = new LinkedHashMap<String, ServiceDefinition>();
        defs.put("gcs", def("gcs", LicenseTier.COMMUNITY));

        service = buildService(LicenseTier.COMMUNITY, defs);
        when(registry.getAllServices()).thenReturn(defs);
        when(config.getConfigSource("gcs")).thenReturn("yaml");
        when(config.isServiceDynamicallyEnabled("gcs")).thenReturn(false);

        HttpResponse response = service.updateServiceConfig("{\"gcs\": true}");
        var agg = response.aggregate().join();

        // Should not be blocked
        assertNotEquals(HttpStatus.FORBIDDEN, agg.status());
        verify(config).setServiceEnabled("gcs", true);
    }
}
