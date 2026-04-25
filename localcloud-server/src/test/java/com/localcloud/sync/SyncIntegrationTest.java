package com.localcloud.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

/**
 * Integration test for the Data Mirror sync flow.
 * Tests SyncService -> SyncAdapter -> SyncManifestRepository chain
 * using mocked adapters and repositories.
 */
class SyncIntegrationTest {

    private SyncService syncService;
    private SyncManifestRepository manifestRepo;
    private SyncCredentialRepository credentialRepo;
    private SyncAdapter mockBqAdapter;

    @BeforeEach
    void setUp() throws Exception {
        manifestRepo = mock(SyncManifestRepository.class);
        credentialRepo = mock(SyncCredentialRepository.class);
        mockBqAdapter = mock(SyncAdapter.class);

        syncService = new SyncService(manifestRepo, credentialRepo, 1.0);
        syncService.registerAdapter("bigquery", mockBqAdapter);

        // Setup credentials
        when(credentialRepo.getCredentialData("test-project"))
                .thenReturn("{\"access_token\":\"test-token-123\"}");
        when(credentialRepo.getStatus("test-project"))
                .thenReturn(Map.of("source_project", "prod-project", "auth_method", "oauth"));
    }

    @Test
    void fullSyncFlow_estimate_then_sync() throws Exception {
        // Arrange: estimate returns acceptable cost
        when(mockBqAdapter.estimate("prod-project", "analytics.events",
                List.of(), 1000, "test-token-123"))
                .thenReturn(new CostEstimate(1000, 5000000, 0.025, "BQ scan: 5 MB"));

        // Arrange: sync returns success
        when(mockBqAdapter.sync(eq("prod-project"), eq("analytics.events"),
                eq(List.of()), eq(1000), eq("test-token-123"), eq("test-project"), any()))
                .thenReturn(new SyncResult(-1, 1000, 5000000, 0.025, "completed", null));

        when(manifestRepo.save(any())).thenReturn(42);

        // Act: estimate
        CostEstimate est = syncService.estimate("test-project", "bigquery",
                "prod-project", "analytics.events", List.of(), 1000);
        assertEquals(0.025, est.estimatedCostUsd(), 0.001);

        // Act: sync
        SyncResult result = syncService.startSync("test-project", "bigquery",
                "prod-project", "analytics.events", List.of(), 1000, null);

        // Assert
        assertEquals("completed", result.status());
        assertEquals(1000, result.rowsSynced());
        assertEquals(42, result.manifestId());

        // Verify manifest lifecycle
        verify(manifestRepo).save(argThat(m ->
                m.projectId().equals("test-project") &&
                m.serviceId().equals("bigquery") &&
                m.resourcePath().equals("analytics.events") &&
                m.status().equals("in_progress")));
        verify(manifestRepo).updateProgress(eq(42), eq("completed"), eq(1000L), eq(5000000L), isNull());
    }

    @Test
    void syncFlow_withFilters() throws Exception {
        List<SyncFilter> filters = List.of(
                new SyncFilter("created_at", ">=", "2026-01-01", "TIMESTAMP"));

        when(mockBqAdapter.estimate("prod-project", "analytics.events",
                filters, 500000, "test-token-123"))
                .thenReturn(new CostEstimate(312000, 1200000000, 0.006, "BQ scan: 1.2 GB"));
        when(mockBqAdapter.sync(eq("prod-project"), eq("analytics.events"),
                eq(filters), eq(500000), eq("test-token-123"), eq("test-project"), any()))
                .thenReturn(new SyncResult(-1, 312000, 1200000000, 0.006, "completed", null));
        when(manifestRepo.save(any())).thenReturn(43);

        SyncResult result = syncService.startSync("test-project", "bigquery",
                "prod-project", "analytics.events", filters, 500000, null);

        assertEquals("completed", result.status());
        assertEquals(312000, result.rowsSynced());
    }

    @Test
    void syncFlow_costCeilingBlocks() throws Exception {
        when(mockBqAdapter.estimate("prod-project", "analytics.events",
                List.of(), 1000, "test-token-123"))
                .thenReturn(new CostEstimate(1000000, 500000000000L, 2.50, "BQ scan: 500 GB"));

        assertThrows(IllegalStateException.class, () ->
                syncService.startSync("test-project", "bigquery",
                        "prod-project", "analytics.events", List.of(), 1000, null));

        // Manifest should NOT be saved
        verify(manifestRepo, never()).save(any());
    }

    @Test
    void syncFlow_adapterFails_manifestMarkedFailed() throws Exception {
        when(mockBqAdapter.estimate("prod-project", "analytics.events",
                List.of(), 100, "test-token-123"))
                .thenReturn(new CostEstimate(100, 5000, 0.001, "small"));
        when(mockBqAdapter.sync(eq("prod-project"), eq("analytics.events"),
                eq(List.of()), eq(100), eq("test-token-123"), eq("test-project"), any()))
                .thenReturn(new SyncResult(-1, 0, 0, 0, "failed", "Network timeout"));
        when(manifestRepo.save(any())).thenReturn(44);

        SyncResult result = syncService.startSync("test-project", "bigquery",
                "prod-project", "analytics.events", List.of(), 100, null);

        assertEquals("failed", result.status());
        assertEquals("Network timeout", result.errorMessage());
        verify(manifestRepo).updateProgress(eq(44), eq("failed"), eq(0L), eq(0L), eq("Network timeout"));
    }

    @Test
    void syncFlow_progressCallback_fires() throws Exception {
        when(mockBqAdapter.estimate("prod-project", "analytics.events",
                List.of(), 1000, "test-token-123"))
                .thenReturn(new CostEstimate(1000, 5000, 0.001, "small"));

        // Simulate adapter calling progress callback
        when(mockBqAdapter.sync(eq("prod-project"), eq("analytics.events"),
                eq(List.of()), eq(1000), eq("test-token-123"), eq("test-project"), any()))
                .thenAnswer(invocation -> {
                    SyncProgressCallback cb = invocation.getArgument(6);
                    if (cb != null) {
                        cb.onProgress(500, 2500, 1000);
                        cb.onProgress(1000, 5000, 1000);
                    }
                    return new SyncResult(-1, 1000, 5000, 0.001, "completed", null);
                });
        when(manifestRepo.save(any())).thenReturn(45);

        // Track external callback calls
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        SyncProgressCallback externalCb = (rows, bytes, total) -> callCount.incrementAndGet();

        syncService.startSync("test-project", "bigquery",
                "prod-project", "analytics.events", List.of(), 1000, externalCb);

        assertEquals(2, callCount.get());
    }

    @Test
    void browseRemote_delegatesCorrectly() throws Exception {
        BrowseResult expected = new BrowseResult(List.of(
                Map.of("id", "analytics", "name", "analytics", "type", "dataset")));
        when(mockBqAdapter.browseRemote("prod-project", "test-token-123"))
                .thenReturn(expected);

        BrowseResult result = syncService.browseRemote("test-project", "bigquery");
        assertEquals(1, result.nodes().size());
        assertEquals("analytics", result.nodes().get(0).get("name"));
    }

    @Test
    void multipleAdapters_routeCorrectly() throws Exception {
        SyncAdapter mockFirestore = mock(SyncAdapter.class);
        syncService.registerAdapter("firestore", mockFirestore);

        BrowseResult bqResult = new BrowseResult(List.of(Map.of("type", "dataset")));
        BrowseResult fsResult = new BrowseResult(List.of(Map.of("type", "collection")));

        when(mockBqAdapter.browseRemote("prod-project", "test-token-123")).thenReturn(bqResult);
        when(mockFirestore.browseRemote("prod-project", "test-token-123")).thenReturn(fsResult);

        assertEquals("dataset", syncService.browseRemote("test-project", "bigquery").nodes().get(0).get("type"));
        assertEquals("collection", syncService.browseRemote("test-project", "firestore").nodes().get(0).get("type"));
    }
}
