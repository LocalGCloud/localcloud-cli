package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.gateway.FaultInjectionRegistry;
import com.localcloud.gateway.RequestLogger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiagnosticsServiceTest {

    private DiagnosticsService service;
    private FaultInjectionRegistry faultRegistry;
    private ServiceRequestContext ctx;

    @BeforeEach
    void setUp() {
        ServiceRegistry registry = ServiceRegistry.load(24080);
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        when(config.getProjectId()).thenReturn("test-project");
        when(config.getGatewayPort()).thenReturn(24080);
        when(config.getDataDir()).thenReturn(Path.of("/tmp/localcloud-test"));
        when(config.isPersistenceEnabled()).thenReturn(true);
        when(config.getIamMode()).thenReturn("permissive");
        when(config.getServiceRegistry()).thenReturn(registry);
        when(config.getConfigSource(anyString())).thenReturn("yaml");
        when(config.isServiceEnabled(anyString())).thenAnswer(invocation -> {
            String serviceId = invocation.getArgument(0, String.class);
            var def = registry.getService(serviceId);
            return def != null && def.defaultEnabled();
        });

        faultRegistry = new FaultInjectionRegistry();
        service = new DiagnosticsService(
                config,
                new RequestLogger(),
                faultRegistry);

        ctx = mock(ServiceRequestContext.class);
        when(ctx.queryParams()).thenReturn(QueryParams.of("limit", "5"));
    }

    @Test
    void diagnosticsIncludesActiveFaults() {
        faultRegistry.add(Map.of("id", "gcs-down", "service", "gcs", "status_code", 503));

        var response = service.diagnostics(ctx).aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
        assertTrue(response.contentUtf8().contains("\"active_faults\""));
        assertTrue(response.contentUtf8().contains("gcs-down"));
    }

    @Test
    void diagnosticsArchiveContainsExpectedEntries() throws Exception {
        faultRegistry.add(Map.of("id", "gcs-down", "service", "gcs", "status_code", 503));

        var response = service.diagnosticsArchive(ctx).aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
        Set<String> names = zipEntryNames(response.content().array());
        assertTrue(names.contains("diagnostics.json"));
        assertTrue(names.contains("coverage.json"));
        assertTrue(names.contains("capabilities.json"));
        assertTrue(names.contains("compatibility.json"));
        assertTrue(names.contains("compatibility-evidence.json"));
        assertTrue(names.contains("requests.json"));
        assertTrue(names.contains("services.json"));
        assertTrue(names.contains("faults.json"));
    }

    private static Set<String> zipEntryNames(byte[] archive) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                names.add(entry.getName());
                entry = zip.getNextEntry();
            }
        }
        return names;
    }
}
