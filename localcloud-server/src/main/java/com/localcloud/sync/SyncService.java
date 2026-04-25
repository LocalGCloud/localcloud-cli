package com.localcloud.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Central orchestrator for Data Mirror sync operations.
 *
 * <p>Coordinates between the REST API layer and per-service {@link SyncAdapter}
 * implementations. Handles cross-cutting concerns:
 * <ul>
 *   <li>Adapter registry — dispatch operations to the correct service adapter
 *   <li>Auth token retrieval — reads OAuth credentials from the credential repository
 *   <li>Cost ceiling enforcement — rejects syncs that exceed the configured cost limit
 *   <li>Manifest lifecycle — creates, updates, and finalizes sync manifests
 *   <li>Progress tracking — maintains real-time progress for active syncs
 * </ul>
 */
public class SyncService {

    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);

    private final SyncManifestRepository manifestRepo;
    private final SyncCredentialRepository credentialRepo;
    private final double costCeilingUsd;
    private final ObjectMapper mapper;

    /** Registered adapters keyed by service ID (e.g., "bigquery", "firestore"). */
    private final ConcurrentHashMap<String, SyncAdapter> adapters = new ConcurrentHashMap<>();

    /** Active sync progress keyed by "projectId:serviceId:resource". */
    private final ConcurrentHashMap<String, SyncProgress> activeProgress = new ConcurrentHashMap<>();

    /** Thread pool for async sync execution. */
    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sync-worker");
        t.setDaemon(true);
        return t;
    });

    /** Active futures for cancellation support (keyed by "projectId:serviceId:resource"). */
    private final Map<String, Future<?>> activeFutures = new ConcurrentHashMap<>();

    public SyncService(SyncManifestRepository manifestRepo,
                       SyncCredentialRepository credentialRepo,
                       double costCeilingUsd) {
        this.manifestRepo = manifestRepo;
        this.credentialRepo = credentialRepo;
        this.costCeilingUsd = costCeilingUsd;
        this.mapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // Adapter registry
    // -----------------------------------------------------------------------

    /**
     * Register a sync adapter for a service.
     *
     * @param serviceId the service identifier (e.g., "bigquery")
     * @param adapter   the adapter implementation
     */
    public void registerAdapter(String serviceId, SyncAdapter adapter) {
        adapters.put(serviceId, adapter);
        logger.info("Registered sync adapter for service: {}", serviceId);
    }

    // -----------------------------------------------------------------------
    // Delegating operations
    // -----------------------------------------------------------------------

    /**
     * Browse the remote project's resources for a service.
     *
     * @param projectId the local project identifier
     * @param serviceId the service to browse
     * @return browse result with available resources
     */
    public BrowseResult browseRemote(String projectId, String serviceId) {
        SyncAdapter adapter = getAdapter(serviceId);
        String accessToken = getAccessToken(projectId);
        String sourceProject = getSourceProject(projectId);
        return adapter.browseRemote(sourceProject, accessToken);
    }

    /**
     * Preview rows from a remote resource.
     *
     * @param projectId the local project identifier
     * @param serviceId the service to preview
     * @param resource  the resource path (e.g., "dataset.table")
     * @param limit     max rows to preview
     * @return preview result with columns and sample rows
     */
    public PreviewResult previewRemote(String projectId, String serviceId,
                                        String resource, int limit) {
        SyncAdapter adapter = getAdapter(serviceId);
        String accessToken = getAccessToken(projectId);
        String sourceProject = getSourceProject(projectId);
        return adapter.previewRemote(sourceProject, resource, accessToken, limit);
    }

    /**
     * Estimate the cost of syncing a resource.
     *
     * @param projectId     the local project identifier
     * @param serviceId     the service to sync
     * @param sourceProject the remote GCP project
     * @param resource      the resource path
     * @param filters       optional filters to narrow the sync
     * @param rowLimit      max rows to sync (0 for unlimited)
     * @return cost estimate with row count, bytes, and USD cost
     */
    public CostEstimate estimate(String projectId, String serviceId, String sourceProject,
                                  String resource, List<SyncFilter> filters, int rowLimit) {
        SyncAdapter adapter = getAdapter(serviceId);
        String accessToken = getAccessToken(projectId);
        return adapter.estimate(sourceProject, resource, filters, rowLimit, accessToken);
    }

    // -----------------------------------------------------------------------
    // Sync execution
    // -----------------------------------------------------------------------

    /**
     * Start sync asynchronously. Returns manifest ID immediately.
     * Poll {@link #getProgress} or use SSE for updates.
     *
     * <p>Performs cost estimation and manifest creation synchronously (fast),
     * then submits the actual data sync to a background thread pool.
     *
     * @param projectId     the local project identifier
     * @param serviceId     the service to sync
     * @param sourceProject the remote GCP project
     * @param resource      the resource path
     * @param filters       optional filters
     * @param rowLimit      max rows (0 for unlimited)
     * @return the manifest ID (can be used to poll progress)
     * @throws IllegalStateException if cost exceeds ceiling or credentials are missing
     */
    public int startSyncAsync(String projectId, String serviceId, String sourceProject,
                               String resource, List<SyncFilter> filters, int rowLimit) {
        SyncAdapter adapter = getAdapter(serviceId);
        String accessToken = getAccessToken(projectId);

        // 1. Cost check (synchronous — fast)
        CostEstimate estimate = adapter.estimate(sourceProject, resource,
                filters, rowLimit, accessToken);

        if (estimate.estimatedCostUsd() > costCeilingUsd) {
            throw new IllegalStateException(String.format(
                    "Estimated cost $%.2f exceeds ceiling $%.2f. " +
                    "Add filters or increase the limit to reduce scan size.",
                    estimate.estimatedCostUsd(), costCeilingUsd));
        }

        // 2. Save manifest as in_progress
        String filtersJson = filtersToJson(filters);
        SyncManifest manifest = new SyncManifest(
                projectId, serviceId, resource, sourceProject,
                filtersJson, 0, 0, estimate.estimatedCostUsd(),
                "in_progress", null);

        int manifestId;
        try {
            manifestId = manifestRepo.save(manifest);
        } catch (SQLException e) {
            logger.error("Failed to save sync manifest: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save sync manifest", e);
        }

        // 3. Run sync in background
        String progressKey = progressKey(projectId, serviceId, resource);
        long startTime = System.currentTimeMillis();

        Future<?> future = syncExecutor.submit(() -> {
            try {
                SyncProgressCallback progressTracker = (rows, bytes, total) -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    int pct = total > 0 ? (int) (rows * 100 / total) : 0;
                    activeProgress.put(progressKey, new SyncProgress(rows, bytes, total, pct, elapsed));
                    try {
                        manifestRepo.updateProgress(manifestId, "in_progress", rows, bytes, null);
                    } catch (Exception e) {
                        logger.warn("Failed to update progress: {}", e.getMessage());
                    }
                };

                SyncResult result = adapter.sync(sourceProject, resource, filters,
                        rowLimit, accessToken, projectId, progressTracker);

                manifestRepo.updateProgress(manifestId, result.status(),
                        result.rowsSynced(), result.bytesSynced(), result.errorMessage());

                // Keep progress for 60 seconds after completion so SSE clients can read it
                activeProgress.put(progressKey, new SyncProgress(
                        result.rowsSynced(), result.bytesSynced(), result.rowsSynced(), 100,
                        System.currentTimeMillis() - startTime));

            } catch (Exception e) {
                logger.error("Sync failed: {}", e.getMessage());
                try {
                    manifestRepo.updateProgress(manifestId, "failed", 0, 0, e.getMessage());
                } catch (Exception ex) {
                    logger.error("Failed to update manifest on error: {}", ex.getMessage());
                }
            } finally {
                activeFutures.remove(progressKey);
                // Delayed cleanup of progress
                syncExecutor.submit(() -> {
                    try { Thread.sleep(60000); } catch (InterruptedException ignored) {}
                    activeProgress.remove(progressKey);
                });
            }
        });

        activeFutures.put(progressKey, future);
        return manifestId;
    }

    /**
     * Execute a sync operation synchronously (blocking).
     *
     * <p>Delegates to {@link #startSyncAsync} and waits for completion.
     * Retained for backward compatibility with tests and CLI callers.
     *
     * @param projectId        the local project identifier
     * @param serviceId        the service to sync
     * @param sourceProject    the remote GCP project
     * @param resource         the resource path
     * @param filters          optional filters
     * @param rowLimit         max rows (0 for unlimited)
     * @param externalCallback optional callback for progress updates (not used in async path)
     * @return sync result with manifest ID and final stats
     */
    public SyncResult startSync(String projectId, String serviceId, String sourceProject,
                                 String resource, List<SyncFilter> filters, int rowLimit,
                                 SyncProgressCallback externalCallback) {
        int manifestId = startSyncAsync(projectId, serviceId, sourceProject, resource, filters, rowLimit);

        // Wait for completion (blocking — used by tests and CLI)
        String syncKey = progressKey(projectId, serviceId, resource);
        Future<?> future = activeFutures.get(syncKey);
        if (future != null) {
            try {
                future.get(300, TimeUnit.SECONDS); // 5 minute timeout
            } catch (Exception e) {
                logger.error("Sync wait interrupted for manifest {}: {}", manifestId, e.getMessage());
            }
        }

        try {
            Map<String, Object> manifest = manifestRepo.getById(manifestId);
            if (manifest != null) {
                return new SyncResult(manifestId,
                        ((Number) manifest.get("row_count")).longValue(),
                        ((Number) manifest.get("bytes_synced")).longValue(),
                        ((Number) manifest.get("estimated_cost")).doubleValue(),
                        (String) manifest.get("status"),
                        (String) manifest.get("error_message"));
            }
        } catch (SQLException e) {
            logger.error("Failed to read manifest {}: {}", manifestId, e.getMessage());
        }

        return new SyncResult(manifestId, 0, 0, 0, "unknown", "Failed to read final manifest");
    }

    // -----------------------------------------------------------------------
    // Progress and manifests
    // -----------------------------------------------------------------------

    /**
     * Get real-time progress of an active sync.
     *
     * @param projectId the local project identifier
     * @param serviceId the service being synced
     * @param resource  the resource path
     * @return current progress or null if no active sync
     */
    public SyncProgress getProgress(String projectId, String serviceId, String resource) {
        return activeProgress.get(progressKey(projectId, serviceId, resource));
    }

    /**
     * Get all sync manifests for a project.
     */
    public List<Map<String, Object>> getManifests(String projectId) {
        try {
            return manifestRepo.getAll(projectId);
        } catch (SQLException e) {
            logger.error("Failed to get manifests for project {}: {}", projectId, e.getMessage(), e);
            throw new RuntimeException("Failed to get sync manifests", e);
        }
    }

    /**
     * Get sync manifests for a project filtered by service.
     */
    public List<Map<String, Object>> getManifests(String projectId, String serviceId) {
        try {
            return manifestRepo.getByService(projectId, serviceId);
        } catch (SQLException e) {
            logger.error("Failed to get manifests for project {} service {}: {}",
                    projectId, serviceId, e.getMessage(), e);
            throw new RuntimeException("Failed to get sync manifests", e);
        }
    }

    /**
     * Delete a sync manifest by id.
     */
    public void deleteManifest(int id) {
        try {
            manifestRepo.delete(id);
        } catch (SQLException e) {
            logger.error("Failed to delete manifest {}: {}", id, e.getMessage(), e);
            throw new RuntimeException("Failed to delete sync manifest", e);
        }
    }

    // -----------------------------------------------------------------------
    // Cancel and resync
    // -----------------------------------------------------------------------

    /**
     * Cancel a running sync. Returns true if a sync was cancelled.
     *
     * @param projectId the local project identifier
     * @param serviceId the service being synced
     * @param resource  the resource path
     * @return true if a running sync was found and cancelled
     */
    public boolean cancelSync(String projectId, String serviceId, String resource) {
        String syncKey = progressKey(projectId, serviceId, resource);
        Future<?> future = activeFutures.get(syncKey);
        if (future != null && !future.isDone()) {
            future.cancel(true); // interrupt the thread
            activeFutures.remove(syncKey);
            activeProgress.remove(syncKey);
            // Update manifest to cancelled
            try {
                var manifests = manifestRepo.getByService(projectId, serviceId);
                for (var m : manifests) {
                    if (resource.equals(m.get("resource_path")) && "in_progress".equals(m.get("status"))) {
                        manifestRepo.updateProgress(
                            ((Number) m.get("id")).intValue(), "cancelled",
                            ((Number) m.get("row_count")).longValue(),
                            ((Number) m.get("bytes_synced")).longValue(),
                            "Cancelled by user");
                        break;
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to update manifest on cancel: {}", e.getMessage());
            }
            return true;
        }
        return false;
    }

    /**
     * Re-run a previous sync using its stored parameters.
     * For partial/cancelled syncs, this starts fresh (resume from checkpoint is a future enhancement).
     *
     * @param manifestId the manifest ID to re-sync
     * @return the new manifest ID
     * @throws Exception if the manifest is not found or sync fails to start
     */
    public int resync(int manifestId) throws Exception {
        Map<String, Object> manifest = manifestRepo.getById(manifestId);
        if (manifest == null) {
            throw new IllegalArgumentException("Manifest not found: " + manifestId);
        }

        String projectId = (String) manifest.get("project_id");
        String serviceId = (String) manifest.get("service_id");
        String resource = (String) manifest.get("resource_path");
        String sourceProject = (String) manifest.get("source_project");
        String filtersJson = (String) manifest.get("filters_json");
        long rowCount = ((Number) manifest.get("row_count")).longValue();

        List<SyncFilter> filters = List.of();
        if (filtersJson != null && !filtersJson.equals("[]")) {
            filters = mapper.readValue(filtersJson,
                mapper.getTypeFactory().constructCollectionType(List.class, SyncFilter.class));
        }

        // Use original row count as limit, or default
        int rowLimit = rowCount > 0 ? (int) Math.min(rowCount * 2, Integer.MAX_VALUE) : 1000000;

        return startSyncAsync(projectId, serviceId, sourceProject, resource, filters, rowLimit);
    }

    // -----------------------------------------------------------------------
    // Inner record
    // -----------------------------------------------------------------------

    /**
     * Real-time progress of an active sync operation.
     */
    public record SyncProgress(long rowsTransferred, long bytesTransferred,
                                long estimatedTotal, int percent, long elapsedMs) {}

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Look up the registered adapter for a service.
     *
     * @throws IllegalArgumentException if no adapter is registered for the service
     */
    private SyncAdapter getAdapter(String serviceId) {
        SyncAdapter adapter = adapters.get(serviceId);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "No sync adapter registered for service: " + serviceId);
        }
        return adapter;
    }

    /**
     * Extract the OAuth access token from stored credentials.
     *
     * @throws IllegalStateException if no credentials exist or the token is missing
     */
    private String getAccessToken(String projectId) {
        try {
            String credentialData = credentialRepo.getCredentialData(projectId);
            if (credentialData == null) {
                throw new IllegalStateException(
                        "No sync credential found for project: " + projectId +
                        ". Connect to a GCP project first via POST /_localcloud/sync/connect.");
            }

            JsonNode json = mapper.readTree(credentialData);
            JsonNode tokenNode = json.get("access_token");
            if (tokenNode == null || tokenNode.isNull() || tokenNode.asText().isEmpty()) {
                throw new IllegalStateException(
                        "Credential data does not contain access_token for project: " + projectId);
            }
            return tokenNode.asText();

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read credential for project " + projectId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Get the source GCP project from credential status.
     *
     * @throws IllegalStateException if no credentials exist
     */
    private String getSourceProject(String projectId) {
        try {
            Map<String, String> status = credentialRepo.getStatus(projectId);
            if (status == null) {
                throw new IllegalStateException(
                        "No sync credential found for project: " + projectId);
            }
            return status.get("source_project");
        } catch (IllegalStateException e) {
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to read credential status for project " + projectId, e);
        }
    }

    /**
     * Build a progress tracking key from project, service, and resource.
     */
    private String progressKey(String projectId, String serviceId, String resource) {
        return projectId + ":" + serviceId + ":" + resource;
    }

    /**
     * Serialize filter list to JSON for manifest storage.
     */
    private String filtersToJson(List<SyncFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(filters);
        } catch (Exception e) {
            logger.warn("Failed to serialize filters to JSON: {}", e.getMessage());
            return "[]";
        }
    }
}
