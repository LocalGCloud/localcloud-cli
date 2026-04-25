package com.localcloud.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * Execute a sync operation with cost ceiling enforcement and manifest tracking.
     *
     * <ol>
     *   <li>Estimates cost via the adapter's dry-run
     *   <li>Rejects if estimated cost exceeds the configured ceiling
     *   <li>Saves a manifest with status "in_progress"
     *   <li>Calls the adapter's sync method with a progress-tracking callback
     *   <li>Updates the manifest to "completed" or "failed"
     * </ol>
     *
     * @param projectId        the local project identifier
     * @param serviceId        the service to sync
     * @param sourceProject    the remote GCP project
     * @param resource         the resource path
     * @param filters          optional filters
     * @param rowLimit         max rows (0 for unlimited)
     * @param externalCallback optional callback for progress updates
     * @return sync result with manifest ID and final stats
     */
    public SyncResult startSync(String projectId, String serviceId, String sourceProject,
                                 String resource, List<SyncFilter> filters, int rowLimit,
                                 SyncProgressCallback externalCallback) {
        SyncAdapter adapter = getAdapter(serviceId);
        String accessToken = getAccessToken(projectId);

        // 1. Estimate cost first
        CostEstimate estimate = adapter.estimate(sourceProject, resource,
                filters, rowLimit, accessToken);

        // 2. Enforce cost ceiling
        if (estimate.estimatedCostUsd() > costCeilingUsd) {
            throw new IllegalStateException(String.format(
                    "Estimated cost $%.2f exceeds ceiling $%.2f. " +
                    "Add filters or increase the limit to reduce scan size.",
                    estimate.estimatedCostUsd(), costCeilingUsd));
        }

        // 3. Save manifest as in_progress
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

        // 4. Build progress-tracking callback
        String progressKey = progressKey(projectId, serviceId, resource);
        long startTime = System.currentTimeMillis();

        SyncProgressCallback progressTracker = (rowsTransferred, bytesTransferred, estimatedTotal) -> {
            long elapsed = System.currentTimeMillis() - startTime;
            int percent = estimatedTotal > 0
                    ? (int) (100 * rowsTransferred / estimatedTotal)
                    : 0;
            activeProgress.put(progressKey, new SyncProgress(
                    rowsTransferred, bytesTransferred, estimatedTotal, percent, elapsed));

            // Forward to external callback if provided
            if (externalCallback != null) {
                externalCallback.onProgress(rowsTransferred, bytesTransferred, estimatedTotal);
            }
        };

        // 5. Execute sync
        SyncResult adapterResult;
        try {
            adapterResult = adapter.sync(sourceProject, resource, filters,
                    rowLimit, accessToken, projectId, progressTracker);
        } finally {
            activeProgress.remove(progressKey);
        }

        // 6. Update manifest with final status
        try {
            manifestRepo.updateProgress(manifestId, adapterResult.status(),
                    adapterResult.rowsSynced(), adapterResult.bytesSynced(),
                    adapterResult.errorMessage());
        } catch (SQLException e) {
            logger.error("Failed to update sync manifest {}: {}", manifestId, e.getMessage(), e);
        }

        // Return result with the real manifest ID
        return new SyncResult(manifestId, adapterResult.rowsSynced(),
                adapterResult.bytesSynced(), adapterResult.costIncurred(),
                adapterResult.status(), adapterResult.errorMessage());
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
