package com.localcloud.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for cancel and resync functionality in {@link SyncService}.
 */
@ExtendWith(MockitoExtension.class)
class SyncCancelResumeTest {

    @Mock SyncManifestRepository manifestRepo;
    @Mock SyncCredentialRepository credentialRepo;
    @Mock SyncAdapter adapter;

    @Test
    void cancelSync_noRunningSync_returnsFalse() {
        SyncService service = new SyncService(manifestRepo, credentialRepo, 1.0);
        assertFalse(service.cancelSync("proj", "bigquery", "ds.tbl"));
    }

    @Test
    void resync_manifestNotFound_throws() throws Exception {
        when(manifestRepo.getById(999)).thenReturn(null);
        SyncService service = new SyncService(manifestRepo, credentialRepo, 1.0);
        assertThrows(IllegalArgumentException.class, () -> service.resync(999));
    }

    @Test
    void resync_reusesManifestParams() throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("project_id", "proj");
        manifest.put("service_id", "bigquery");
        manifest.put("resource_path", "ds.tbl");
        manifest.put("source_project", "prod-123");
        manifest.put("filters_json", "[]");
        manifest.put("row_count", 5000L);
        manifest.put("bytes_synced", 10000L);
        manifest.put("estimated_cost", 0.01);
        manifest.put("status", "completed");

        when(manifestRepo.getById(1)).thenReturn(manifest);
        when(credentialRepo.getCredentialData("proj")).thenReturn("{\"access_token\":\"tok\"}");
        lenient().when(credentialRepo.getStatus("proj")).thenReturn(Map.of("source_project", "prod-123"));
        when(adapter.estimate(any(), any(), any(), anyInt(), any()))
                .thenReturn(new CostEstimate(5000, 10000, 0.01, "ok"));
        when(manifestRepo.save(any())).thenReturn(2);
        // sync runs in a background thread — may not be consumed before test returns
        lenient().when(adapter.sync(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(new SyncResult(-1, 5000, 10000, 0.01, "completed", null));

        SyncService service = new SyncService(manifestRepo, credentialRepo, 1.0);
        service.registerAdapter("bigquery", adapter);

        int newId = service.resync(1);
        assertEquals(2, newId);
        verify(manifestRepo).save(argThat(m ->
            m.serviceId().equals("bigquery") && m.resourcePath().equals("ds.tbl")));
    }
}
