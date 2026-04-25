package com.localcloud.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SyncService}.
 *
 * <p>Verifies orchestration logic: adapter dispatch, cost ceiling enforcement,
 * manifest lifecycle, credential lookup, and progress tracking.
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock SyncManifestRepository manifestRepo;
    @Mock SyncCredentialRepository credentialRepo;
    @Mock SyncAdapter bigqueryAdapter;

    private SyncService service;

    private static final String PROJECT = "test-project";
    private static final String SERVICE = "bigquery";
    private static final String RESOURCE = "dataset.table";
    private static final String SOURCE_PROJECT = "prod-gcp-project";
    private static final double COST_CEILING = 1.0;

    @BeforeEach
    void setUp() {
        service = new SyncService(manifestRepo, credentialRepo, COST_CEILING);
        service.registerAdapter(SERVICE, bigqueryAdapter);
    }

    // -----------------------------------------------------------------------
    // estimate — delegates to correct adapter
    // -----------------------------------------------------------------------

    @Test
    void estimate_delegatesToCorrectAdapter() throws Exception {
        CostEstimate expected = new CostEstimate(1000, 5_000_000, 0.05, "details");
        String credJson = "{\"access_token\":\"tok123\"}";

        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(credJson);
        when(bigqueryAdapter.estimate(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(0), eq("tok123"))).thenReturn(expected);

        CostEstimate result = service.estimate(PROJECT, SERVICE, SOURCE_PROJECT, RESOURCE, List.of(), 0);
        assertEquals(expected, result);
        verify(bigqueryAdapter).estimate(SOURCE_PROJECT, RESOURCE, List.of(), 0, "tok123");
    }

    // -----------------------------------------------------------------------
    // startSync — cost ceiling enforcement
    // -----------------------------------------------------------------------

    @Test
    void startSync_rejectsCostAboveCeiling() throws Exception {
        CostEstimate expensive = new CostEstimate(1_000_000, 500_000_000_000L, 2.50, "too expensive");
        String credJson = "{\"access_token\":\"tok123\"}";

        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(credJson);
        when(bigqueryAdapter.estimate(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(0), eq("tok123"))).thenReturn(expensive);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.startSync(PROJECT, SERVICE, SOURCE_PROJECT,
                        RESOURCE, List.of(), 0, null));

        assertTrue(ex.getMessage().contains("2.50"));
        assertTrue(ex.getMessage().contains("1.00"));
        verify(manifestRepo, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // startSync — manifest lifecycle
    // -----------------------------------------------------------------------

    @Test
    void startSync_savesManifest() throws Exception {
        CostEstimate cheap = new CostEstimate(100, 1000, 0.01, "small");
        SyncResult adapterResult = new SyncResult(0, 100, 1000, 0.01, "completed", null);
        String credJson = "{\"access_token\":\"tok123\"}";

        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(credJson);
        when(bigqueryAdapter.estimate(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(500), eq("tok123"))).thenReturn(cheap);
        when(manifestRepo.save(any(SyncManifest.class))).thenReturn(42);
        when(bigqueryAdapter.sync(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(500), eq("tok123"), eq(PROJECT),
                any(SyncProgressCallback.class))).thenReturn(adapterResult);

        SyncResult result = service.startSync(PROJECT, SERVICE, SOURCE_PROJECT,
                RESOURCE, List.of(), 500, null);

        assertEquals("completed", result.status());
        assertEquals(42, result.manifestId());
        assertEquals(100, result.rowsSynced());

        // Manifest should be saved initially as in_progress
        verify(manifestRepo).save(argThat(m ->
                "in_progress".equals(m.status()) &&
                PROJECT.equals(m.projectId()) &&
                SERVICE.equals(m.serviceId()) &&
                RESOURCE.equals(m.resourcePath())));

        // Manifest should be updated to completed
        verify(manifestRepo).updateProgress(eq(42), eq("completed"),
                eq(100L), eq(1000L), isNull());
    }

    // -----------------------------------------------------------------------
    // getAdapter — unknown service
    // -----------------------------------------------------------------------

    @Test
    void getAdapter_unknownService_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.browseRemote(PROJECT, "unknown-service"));
    }

    // -----------------------------------------------------------------------
    // browseRemote — no credentials
    // -----------------------------------------------------------------------

    @Test
    void browseRemote_noCredentials_throws() throws Exception {
        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.browseRemote(PROJECT, SERVICE));

        assertTrue(ex.getMessage().contains("credential"));
    }

    // -----------------------------------------------------------------------
    // startSync — adapter failure updates manifest to failed
    // -----------------------------------------------------------------------

    @Test
    void startSync_adapterFails_updatesManifestToFailed() throws Exception {
        CostEstimate cheap = new CostEstimate(100, 1000, 0.01, "small");
        SyncResult failedResult = new SyncResult(0, 50, 500, 0.005,
                "failed", "Connection timeout");
        String credJson = "{\"access_token\":\"tok123\"}";

        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(credJson);
        when(bigqueryAdapter.estimate(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(0), eq("tok123"))).thenReturn(cheap);
        when(manifestRepo.save(any(SyncManifest.class))).thenReturn(7);
        when(bigqueryAdapter.sync(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(0), eq("tok123"), eq(PROJECT),
                any(SyncProgressCallback.class))).thenReturn(failedResult);

        SyncResult result = service.startSync(PROJECT, SERVICE, SOURCE_PROJECT,
                RESOURCE, List.of(), 0, null);

        assertEquals("failed", result.status());
        assertEquals(7, result.manifestId());

        // Manifest should be updated to failed with error message
        verify(manifestRepo).updateProgress(eq(7), eq("failed"),
                eq(50L), eq(500L), eq("Connection timeout"));
    }

    // -----------------------------------------------------------------------
    // browseRemote — delegates correctly
    // -----------------------------------------------------------------------

    @Test
    void browseRemote_delegatesToAdapter() throws Exception {
        BrowseResult expected = new BrowseResult(List.of(Map.of("id", "ds1")));
        String credJson = "{\"access_token\":\"tok-browse\"}";

        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(credJson);
        when(bigqueryAdapter.browseRemote(SOURCE_PROJECT, "tok-browse")).thenReturn(expected);
        when(credentialRepo.getStatus(PROJECT)).thenReturn(
                Map.of("source_project", SOURCE_PROJECT));

        BrowseResult result = service.browseRemote(PROJECT, SERVICE);
        assertEquals(expected, result);
    }

    // -----------------------------------------------------------------------
    // previewRemote — delegates correctly
    // -----------------------------------------------------------------------

    @Test
    void previewRemote_delegatesToAdapter() throws Exception {
        PreviewResult expected = new PreviewResult(
                List.of("col1"), List.of(Map.of("col1", "val")), 1, 100);
        String credJson = "{\"access_token\":\"tok-preview\"}";

        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(credJson);
        when(bigqueryAdapter.previewRemote(SOURCE_PROJECT, RESOURCE, "tok-preview", 10))
                .thenReturn(expected);
        when(credentialRepo.getStatus(PROJECT)).thenReturn(
                Map.of("source_project", SOURCE_PROJECT));

        PreviewResult result = service.previewRemote(PROJECT, SERVICE, RESOURCE, 10);
        assertEquals(expected, result);
    }

    // -----------------------------------------------------------------------
    // getManifests — delegates to repo
    // -----------------------------------------------------------------------

    @Test
    void getManifests_byProject_delegatesToRepo() throws Exception {
        List<Map<String, Object>> expected = List.of(Map.of("id", 1));
        when(manifestRepo.getAll(PROJECT)).thenReturn(expected);

        List<Map<String, Object>> result = service.getManifests(PROJECT);
        assertEquals(expected, result);
    }

    @Test
    void getManifests_byProjectAndService_delegatesToRepo() throws Exception {
        List<Map<String, Object>> expected = List.of(Map.of("id", 2));
        when(manifestRepo.getByService(PROJECT, SERVICE)).thenReturn(expected);

        List<Map<String, Object>> result = service.getManifests(PROJECT, SERVICE);
        assertEquals(expected, result);
    }

    // -----------------------------------------------------------------------
    // deleteManifest
    // -----------------------------------------------------------------------

    @Test
    void deleteManifest_delegatesToRepo() throws Exception {
        service.deleteManifest(99);
        verify(manifestRepo).delete(99);
    }

    // -----------------------------------------------------------------------
    // startSync — progress tracking
    // -----------------------------------------------------------------------

    @Test
    void startSync_tracksProgress() throws Exception {
        CostEstimate cheap = new CostEstimate(1000, 10000, 0.05, "ok");
        SyncResult adapterResult = new SyncResult(0, 1000, 10000, 0.05, "completed", null);
        String credJson = "{\"access_token\":\"tok123\"}";

        when(credentialRepo.getCredentialData(PROJECT)).thenReturn(credJson);
        when(bigqueryAdapter.estimate(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(0), eq("tok123"))).thenReturn(cheap);
        when(manifestRepo.save(any(SyncManifest.class))).thenReturn(10);
        when(bigqueryAdapter.sync(eq(SOURCE_PROJECT), eq(RESOURCE),
                anyList(), eq(0), eq("tok123"), eq(PROJECT),
                any(SyncProgressCallback.class))).thenAnswer(invocation -> {
            // Simulate progress callback from the adapter
            SyncProgressCallback cb = invocation.getArgument(6);
            cb.onProgress(500, 5000, 1000);
            cb.onProgress(1000, 10000, 1000);
            return adapterResult;
        });

        SyncProgressCallback externalCallback = mock(SyncProgressCallback.class);
        service.startSync(PROJECT, SERVICE, SOURCE_PROJECT,
                RESOURCE, List.of(), 0, externalCallback);

        // External callback should have been invoked
        verify(externalCallback).onProgress(500, 5000, 1000);
        verify(externalCallback).onProgress(1000, 10000, 1000);
    }

    // -----------------------------------------------------------------------
    // credential JSON parsing edge cases
    // -----------------------------------------------------------------------

    @Test
    void estimate_credentialJsonMissingAccessToken_throws() throws Exception {
        when(credentialRepo.getCredentialData(PROJECT)).thenReturn("{\"refresh_token\":\"abc\"}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.estimate(PROJECT, SERVICE, SOURCE_PROJECT, RESOURCE, List.of(), 0));

        assertTrue(ex.getMessage().contains("access_token"));
    }

    @Test
    void estimate_credentialJsonInvalid_throws() throws Exception {
        when(credentialRepo.getCredentialData(PROJECT)).thenReturn("not-json");

        assertThrows(IllegalStateException.class,
                () -> service.estimate(PROJECT, SERVICE, SOURCE_PROJECT, RESOURCE, List.of(), 0));
    }
}
