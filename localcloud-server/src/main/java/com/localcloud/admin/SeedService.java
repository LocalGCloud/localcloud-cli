package com.localcloud.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.ByteString;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.emulators.alloydb.AlloyDBEmulator;
import com.localcloud.emulators.dataproc.DataprocEmulator;
import com.localcloud.emulators.functions.CloudFunctionsEmulator;
import com.localcloud.emulators.iam.IAMEmulator;
import com.localcloud.emulators.scheduler.CloudSchedulerEmulator;
import com.localcloud.emulators.workflows.WorkflowsStore;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Armeria annotated service for seeding and resetting emulator data.
 * Seeds data by calling external emulator REST APIs (GCS, Pub/Sub, BigQuery)
 * and in-process stores (Secret Manager).
 */
public class SeedService {

    private static final Logger logger = LoggerFactory.getLogger(SeedService.class);

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final ServiceRegistry registry;
    private final WorkflowsStore workflowsStore;
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;
    private final HttpClient httpClient;
    private volatile CloudSchedulerEmulator schedulerEmulator;
    private volatile CloudFunctionsEmulator functionsEmulator;
    private volatile AlloyDBEmulator alloyDBEmulator;
    private volatile DataprocEmulator dataprocEmulator;
    private volatile IAMEmulator iamEmulator;

    // Base URLs computed from registry
    private final String gcsBase;
    private final String pubsubBase;
    private final String bigqueryBase;
    private final int spannerRestPort;
    private final int firestorePort;
    private final int bigtablePort;

    /** Stores the last loaded seed YAML so it can be restored on reset. */
    private volatile String lastSeedYaml;

    public SeedService(LocalCloudConfig config, PostgresDataSource dataSource, ServiceRegistry registry,
                       WorkflowsStore workflowsStore) {
        this.config = config;
        this.dataSource = dataSource;
        this.registry = registry;
        this.workflowsStore = workflowsStore;
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.registerModule(new JavaTimeModule());
        this.jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.yamlMapper = new YAMLMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // Compute base URLs from registry definitions
        this.gcsBase = baseUrl(registry.getService("gcs"));
        this.pubsubBase = baseUrl(registry.getService("pubsub"));
        this.bigqueryBase = baseUrl(registry.getService("bigquery"));

        ServiceDefinition spannerDef = registry.getService("spanner");
        this.spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;

        ServiceDefinition firestoreDef = registry.getService("firestore");
        this.firestorePort = firestoreDef != null ? firestoreDef.port() : 8086;

        ServiceDefinition bigtableDef = registry.getService("bigtable");
        this.bigtablePort = bigtableDef != null ? bigtableDef.port() : 8087;
    }

    private static String baseUrl(ServiceDefinition def) {
        if (def == null) return "http://localhost:0";
        return "http://localhost:" + def.port();
    }

    public void setCloudSchedulerEmulator(CloudSchedulerEmulator schedulerEmulator) {
        this.schedulerEmulator = schedulerEmulator;
    }

    public void setCloudFunctionsEmulator(CloudFunctionsEmulator functionsEmulator) {
        this.functionsEmulator = functionsEmulator;
    }

    public void setAlloyDBEmulator(AlloyDBEmulator alloyDBEmulator) {
        this.alloyDBEmulator = alloyDBEmulator;
    }

    public void setDataprocEmulator(DataprocEmulator dataprocEmulator) {
        this.dataprocEmulator = dataprocEmulator;
    }

    public void setIAMEmulator(IAMEmulator iamEmulator) {
        this.iamEmulator = iamEmulator;
    }

    /**
     * Accept YAML seed data and load it into emulators via their REST APIs.
     * <pre>
     * gcs:
     *   buckets:
     *     - name: my-bucket
     *       location: US
     * pubsub:
     *   topics:
     *     - project: local-project
     *       name: my-topic
     *   subscriptions:
     *     - project: local-project
     *       name: my-sub
     *       topic: projects/local-project/topics/my-topic
     * bigquery:
     *   datasets:
     *     - name: my_dataset
     *   tables:
     *     - dataset: my_dataset
     *       name: my_table
     *       schema:
     *         fields:
     *           - name: id
     *             type: INTEGER
     * </pre>
     */
    /**
     * Re-seed from the baked-in seed file at /etc/localcloud/seed.yaml.
     * Called from the console Settings page "Re-seed" button.
     */
    @Post("/reseed")
    public com.linecorp.armeria.common.HttpResponse reseed() {
        try {
            java.io.File seedFile = new java.io.File(
                System.getenv().getOrDefault("LOCALCLOUD_SEED_FILE", "/etc/localcloud/seed.yaml"));
            if (!seedFile.exists()) {
                return errorResponse(HttpStatus.NOT_FOUND, "No seed file found at " + seedFile.getAbsolutePath());
            }
            String yamlContent = java.nio.file.Files.readString(seedFile.toPath());
            // Delegate to the main seed method by creating a fake request
            return doSeed(yamlContent);
        } catch (Exception e) {
            logger.error("Re-seed failed", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Re-seed failed: " + e.getMessage());
        }
    }

    /**
     * Seed all services from YAML body.
     * Query params:
     *   ?mode=volatile  — only seed in-memory services (Pub/Sub, Firestore, Bigtable).
     *                     Skip persistent services (GCS, BigQuery, Spanner, PostgreSQL-backed).
     *                     Used by auto-seed on container restart.
     *   ?mode=all       — seed everything (default).
     */
    @Post("/seed")
    public com.linecorp.armeria.common.HttpResponse seed(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        String yamlContent = request.contentUtf8();
        if (yamlContent == null || yamlContent.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "Seed data is required (YAML format)");
        }
        String mode = ctx.queryParam("mode");
        boolean volatileOnly = "volatile".equals(mode);
        return doSeed(yamlContent, volatileOnly);
    }

    @Post("/import")
    public com.linecorp.armeria.common.HttpResponse importState(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        return seed(ctx, request);
    }

    private com.linecorp.armeria.common.HttpResponse doSeed(String yamlContent) {
        return doSeed(yamlContent, false);
    }

    private com.linecorp.armeria.common.HttpResponse doSeed(String yamlContent, boolean volatileOnly) {
        try {
            Map<String, Object> response = seedYaml(yamlContent, volatileOnly);
            return jsonResponse(HttpStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error processing seed data", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Seed failed: " + e.getMessage());
        }
    }

    public Map<String, Object> seedYaml(String yamlContent, boolean volatileOnly) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> rawData = yamlMapper.readValue(yamlContent, Map.class);

        // Multi-project format: projects: { dev: { gcs: ... }, staging: { gcs: ... } }
        // Single-project format: services: { gcs: ... } or flat { gcs: ... }
        int totalSeeded = 0;
        Map<String, Object> results = new LinkedHashMap<>();

        if (rawData.containsKey("projects") && rawData.get("projects") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> projectsMap = (Map<String, Object>) rawData.get("projects");
            for (Map.Entry<String, Object> entry : projectsMap.entrySet()) {
                String projectId = entry.getKey();
                if (!(entry.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> projectServices = (Map<String, Object>) entry.getValue();
                @SuppressWarnings("unchecked")
                Map<String, Object> seedData = projectServices.containsKey("services") && projectServices.get("services") instanceof Map
                        ? (Map<String, Object>) projectServices.get("services")
                        : projectServices;
                totalSeeded += seedServicesForProject(seedData, projectId, results, volatileOnly);
            }
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> seedData = rawData.containsKey("services") && rawData.get("services") instanceof Map
                    ? (Map<String, Object>) rawData.get("services")
                    : rawData;
            totalSeeded = seedServicesForProject(seedData, config.getProjectId(), results, volatileOnly);
        }

        lastSeedYaml = yamlContent;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "seeded");
        response.put("total_records", totalSeeded);
        response.put("services", results);
        return response;
    }

    /**
     * Seed all services for a specific project.
     * Note: For multi-project, this is called once per project.
     * The individual seed methods use config.getProjectId() internally,
     * so for now multi-project seeding seeds under the default project.
     * Full per-project seeding would require passing projectId through
     * each seed method — marked as a future enhancement.
     */
    private int seedServicesForProject(Map<String, Object> seedData, String projectId,
                                        Map<String, Object> results, boolean volatileOnly) {
        // Persistent services — data survives container restarts (filesystem, DuckDB, LevelDB, PostgreSQL).
        // Skip these on restart (volatileOnly=true) since their data is already on the persistent volume.
        // Volatile services — in-memory emulators that lose data on restart (gcloud Pub/Sub, Bigtable).
        // These must always be re-seeded.
        //
        // Service storage map:
        //   GCS:            filesystem (/var/lib/localcloud/gcs-data)     → persistent
        //   BigQuery:       DuckDB (/var/lib/localcloud/bigquery-data)    → persistent
        //   Spanner:        LevelDB (/var/lib/localcloud/spanner-data)    → persistent
        //   Secret Manager: PostgreSQL                                    → persistent
        //   Cloud Tasks:    PostgreSQL                                    → persistent
        //   Memorystore:    PostgreSQL (redis_data table)                 → persistent
        //   Workflows:      PostgreSQL                                    → persistent
        //   Logging:        PostgreSQL                                    → persistent
        //   Monitoring:     PostgreSQL                                    → persistent
        //   Pub/Sub:        in-memory (gcloud emulator)                   → volatile
        //   Bigtable:       in-memory (gcloud emulator)                   → volatile
        //   Firestore:      NOT IMPLEMENTED — emulator starts but seeding is disabled

        int totalSeeded = 0;

        // --- Volatile services (always seed) ---
        if (seedData.containsKey("pubsub")) {
            int count = seedPubSub(seedData.get("pubsub"));
            results.put("pubsub", results.containsKey("pubsub") ? ((int) results.get("pubsub")) + count : count);
            totalSeeded += count;
        }
        // Firestore emulator is not fully implemented — skip seeding to avoid
        // blocking on an unresponsive emulator process.
        if (seedData.containsKey("firestore")) {
            logger.info("Skipping Firestore seed — emulator not yet implemented");
        }
        if (seedData.containsKey("bigtable")) {
            int count = seedBigtable(seedData.get("bigtable"));
            results.put("bigtable", results.containsKey("bigtable") ? ((int) results.get("bigtable")) + count : count);
            totalSeeded += count;
        }

        // --- Persistent services (skip on restart when volatileOnly=true) ---
        if (!volatileOnly) {
            if (seedData.containsKey("gcs")) {
                int count = seedGcs(seedData.get("gcs"));
                results.put("gcs", results.containsKey("gcs") ? ((int) results.get("gcs")) + count : count);
                totalSeeded += count;
            }
            if (seedData.containsKey("bigquery")) {
                int count = seedBigQuery(seedData.get("bigquery"));
                results.put("bigquery", results.containsKey("bigquery") ? ((int) results.get("bigquery")) + count : count);
                totalSeeded += count;
            }
            if (seedData.containsKey("spanner")) {
                int count = seedSpanner(seedData.get("spanner"));
                results.put("spanner", results.containsKey("spanner") ? ((int) results.get("spanner")) + count : count);
                totalSeeded += count;
            }
            if (seedData.containsKey("secretmanager")) {
                int count = seedSecretManager(seedData.get("secretmanager"));
                results.put("secretmanager", results.containsKey("secretmanager") ? ((int) results.get("secretmanager")) + count : count);
                totalSeeded += count;
            }
            if (seedData.containsKey("cloudtasks")) {
                int count = seedCloudTasks(seedData.get("cloudtasks"));
                results.put("cloudtasks", results.containsKey("cloudtasks") ? ((int) results.get("cloudtasks")) + count : count);
                totalSeeded += count;
            }
            if (seedData.containsKey("memorystore")) {
                int count = seedMemorystore(seedData.get("memorystore"));
                results.put("memorystore", results.containsKey("memorystore") ? ((int) results.get("memorystore")) + count : count);
                totalSeeded += count;
            }
            if (seedData.containsKey("workflows")) {
                int count = seedWorkflows(seedData.get("workflows"), projectId);
                results.put("workflows", results.containsKey("workflows") ? ((int) results.get("workflows")) + count : count);
                totalSeeded += count;
            }
            Object schedulerSeed = first(seedData, "cloudscheduler", "scheduler");
            if (schedulerSeed != null) {
                int count = seedCloudScheduler(schedulerSeed, projectId);
                addResult(results, "cloudscheduler", count);
                totalSeeded += count;
            }
            Object functionsSeed = first(seedData, "cloudfunctions", "functions");
            if (functionsSeed != null) {
                int count = seedCloudFunctions(functionsSeed, projectId);
                addResult(results, "cloudfunctions", count);
                totalSeeded += count;
            }
            if (seedData.containsKey("alloydb")) {
                int count = seedAlloyDB(seedData.get("alloydb"), projectId);
                addResult(results, "alloydb", count);
                totalSeeded += count;
            }
            if (seedData.containsKey("dataproc")) {
                int count = seedDataproc(seedData.get("dataproc"), projectId);
                addResult(results, "dataproc", count);
                totalSeeded += count;
            }
            Object iamSeed = first(seedData, "cloudiam", "iam");
            if (iamSeed != null) {
                int count = seedCloudIAM(iamSeed);
                addResult(results, "cloudiam", count);
                totalSeeded += count;
            }
        } else {
            logger.info("Volatile-only seed: skipping persistent services (GCS, BigQuery, Spanner, SecretManager, CloudTasks, Memorystore, Workflows, Scheduler, Functions, AlloyDB, Dataproc, IAM)");
        }

        return totalSeeded;
    }

    /**
     * Reset emulator data. Accepts a JSON body with an optional
     * {@code restore_seed} boolean field. If true, re-applies
     * the last loaded seed after clearing.
     */
    @Post("/reset")
    public com.linecorp.armeria.common.HttpResponse reset(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            boolean restoreSeed = false;
            String body = request.contentUtf8();
            if (body != null && !body.isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> jsonBody = jsonMapper.readValue(body, Map.class);
                    Object val = jsonBody.get("restore_seed");
                    restoreSeed = Boolean.TRUE.equals(val) || "true".equals(String.valueOf(val));
                } catch (Exception ignored) {
                    // fall through with restoreSeed = false
                }
            }

            // Resolve project ID: use ?project= query param if provided, else config default
            String projectParam = ctx.queryParams().get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();

            // Clear all data for the target project
            int cleared = resetProjectData(projectId);
            logger.info("Reset: cleared {} rows for project '{}'", cleared, projectId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("rows_cleared", cleared);

            if (restoreSeed && lastSeedYaml != null) {
                // Re-apply seed
                @SuppressWarnings("unchecked")
                Map<String, Object> rawData = yamlMapper.readValue(lastSeedYaml, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> seedData = rawData.containsKey("services") && rawData.get("services") instanceof Map
                        ? (Map<String, Object>) rawData.get("services")
                        : rawData;
                Map<String, Object> restored = new LinkedHashMap<>();
                int totalSeeded = seedServicesForProject(seedData, projectId, restored, false);

                response.put("seed_restored", true);
                response.put("records_restored", totalSeeded);
            } else {
                response.put("seed_restored", false);
            }

            return jsonResponse(HttpStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error resetting data", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Reset failed: " + e.getMessage());
        }
    }

    public int resetProjectData(String projectId) {
        int cleared = 0;
        cleared += resetGcs(projectId);
        cleared += resetPubSub(projectId);
        cleared += resetFirestore(projectId);
        cleared += resetBigQuery(projectId);
        cleared += resetSpanner(projectId);
        cleared += resetSecretManager(projectId);
        cleared += resetCloudTasks(projectId);
        cleared += resetLogging(projectId);
        cleared += resetMonitoring(projectId);
        cleared += resetMemorystore(projectId);
        cleared += resetBigtable(projectId);
        cleared += resetCompute(projectId);
        cleared += resetCloudRun(projectId);
        cleared += resetGke(projectId);
        cleared += resetWorkflows(projectId);
        cleared += resetCloudScheduler(projectId);
        cleared += resetCloudFunctions(projectId);
        cleared += resetAlloyDB(projectId);
        cleared += resetDataproc(projectId);
        cleared += resetCloudIAM(projectId);
        return cleared;
    }

    /**
     * Reset data for a single service. Accepts a JSON body with an optional
     * {@code restore_seed} boolean field. If true, re-applies the last loaded
     * seed data for that specific service after clearing.
     *
     * <p>PostgreSQL-backed services have their tables truncated via parameterized DELETE.
     * External emulators have their resources deleted via REST API calls.</p>
     */
    @Post("/reset/{service}")
    public com.linecorp.armeria.common.HttpResponse resetService(
            ServiceRequestContext ctx, @Param("service") String service, AggregatedHttpRequest request) {
        try {
            // Validate service name
            ServiceDefinition def = registry.getService(service);
            if (def == null) {
                return errorResponse(HttpStatus.NOT_FOUND, "Unknown service: " + service);
            }

            // Parse restore_seed from JSON body
            boolean restoreSeed = false;
            String body = request.contentUtf8();
            if (body != null && !body.isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> jsonBody = jsonMapper.readValue(body, Map.class);
                    Object val = jsonBody.get("restore_seed");
                    restoreSeed = Boolean.TRUE.equals(val) || "true".equals(String.valueOf(val));
                } catch (Exception ignored) {
                    // fall through with restoreSeed = false
                }
            }

            // Resolve project ID: use ?project= query param if provided, else config default
            String projectParam = ctx.queryParams().get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();

            // Dispatch to per-service reset logic
            int deletedCount = switch (service) {
                case "gcs" -> resetGcs(projectId);
                case "pubsub" -> resetPubSub(projectId);
                case "firestore" -> resetFirestore(projectId);
                case "bigquery" -> resetBigQuery(projectId);
                case "spanner" -> resetSpanner(projectId);
                case "secretmanager" -> resetSecretManager(projectId);
                case "cloudtasks" -> resetCloudTasks(projectId);
                case "logging" -> resetLogging(projectId);
                case "monitoring" -> resetMonitoring(projectId);
                case "memorystore" -> resetMemorystore(projectId);
                case "bigtable" -> resetBigtable(projectId);
                case "compute" -> resetCompute(projectId);
                case "cloudrun" -> resetCloudRun(projectId);
                case "gke" -> resetGke(projectId);
                case "workflows" -> resetWorkflows(projectId);
                case "cloudscheduler" -> resetCloudScheduler(projectId);
                case "cloudfunctions" -> resetCloudFunctions(projectId);
                case "alloydb" -> resetAlloyDB(projectId);
                case "dataproc" -> resetDataproc(projectId);
                case "cloudiam" -> resetCloudIAM(projectId);
                default -> {
                    logger.warn("No reset logic for service: {}", service);
                    yield 0;
                }
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("service", service);
            response.put("deleted_count", deletedCount);

            // Optionally re-seed this service
            if (restoreSeed && lastSeedYaml != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawData = yamlMapper.readValue(lastSeedYaml, Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> seedData = rawData.containsKey("services") && rawData.get("services") instanceof Map
                            ? (Map<String, Object>) rawData.get("services")
                            : rawData;

                    int seededCount = 0;
                    if (seedData.containsKey(service)) {
                        seededCount = switch (service) {
                            case "gcs" -> seedGcs(seedData.get("gcs"));
                            case "pubsub" -> seedPubSub(seedData.get("pubsub"));
                            case "bigquery" -> seedBigQuery(seedData.get("bigquery"));
                            case "secretmanager" -> seedSecretManager(seedData.get("secretmanager"));
                            case "memorystore" -> seedMemorystore(seedData.get("memorystore"));
                            case "spanner" -> seedSpanner(seedData.get("spanner"));
                            case "firestore" -> {
                                logger.info("Skipping Firestore seed — emulator not yet implemented");
                                yield 0;
                            }
                            case "bigtable" -> seedBigtable(seedData.get("bigtable"));
                            case "cloudtasks" -> seedCloudTasks(seedData.get("cloudtasks"));
                            case "workflows" -> seedWorkflows(seedData.get("workflows"), config.getProjectId());
                            case "cloudscheduler" -> seedCloudScheduler(seedData.get("cloudscheduler"), config.getProjectId());
                            case "cloudfunctions" -> seedCloudFunctions(seedData.get("cloudfunctions"), config.getProjectId());
                            case "alloydb" -> seedAlloyDB(seedData.get("alloydb"), config.getProjectId());
                            case "dataproc" -> seedDataproc(seedData.get("dataproc"), config.getProjectId());
                            case "cloudiam" -> seedCloudIAM(seedData.get("cloudiam"));
                            default -> 0;
                        };
                    }
                    response.put("seed_restored", true);
                    response.put("records_restored", seededCount);
                } catch (Exception e) {
                    logger.warn("Failed to restore seed for {}: {}", service, e.getMessage());
                    response.put("seed_restored", false);
                    response.put("seed_error", e.getMessage());
                }
            } else {
                response.put("seed_restored", false);
            }

            return jsonResponse(HttpStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error resetting service {}", service, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Reset failed for " + service + ": " + e.getMessage());
        }
    }

    // ========== Per-service reset methods ==========

    @SuppressWarnings("unchecked")
    private int resetGcs(String projectId) {
        int count = 0;
        try {
            // List all buckets
            String listUrl = gcsBase + "/storage/v1/b?project=" + projectId;
            String resp = httpGet(listUrl);
            Map<String, Object> parsed = jsonMapper.readValue(resp, Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) parsed.get("items");
            if (items != null) {
                for (Map<String, Object> bucket : items) {
                    String bucketName = (String) bucket.get("name");
                    // List and delete all objects in the bucket
                    try {
                        String objListUrl = gcsBase + "/storage/v1/b/" + bucketName + "/o";
                        String objResp = httpGet(objListUrl);
                        Map<String, Object> objParsed = jsonMapper.readValue(objResp, Map.class);
                        List<Map<String, Object>> objects = (List<Map<String, Object>>) objParsed.get("items");
                        if (objects != null) {
                            for (Map<String, Object> obj : objects) {
                                String objName = (String) obj.get("name");
                                try {
                                    String encodedName = java.net.URLEncoder.encode(objName, StandardCharsets.UTF_8);
                                    httpDelete(gcsBase + "/storage/v1/b/" + bucketName + "/o/" + encodedName);
                                    count++;
                                } catch (Exception e) {
                                    logger.debug("Failed to delete GCS object {}/{}: {}", bucketName, objName, e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to list objects in bucket {}: {}", bucketName, e.getMessage());
                    }
                    // Delete the bucket
                    try {
                        httpDelete(gcsBase + "/storage/v1/b/" + bucketName);
                        count++;
                    } catch (Exception e) {
                        logger.debug("Failed to delete GCS bucket {}: {}", bucketName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset GCS: {}", e.getMessage());
        }
        logger.info("Reset GCS: deleted {} resources", count);
        return count;
    }

    @SuppressWarnings("unchecked")
    private int resetPubSub(String projectId) {
        int count = 0;
        try {
            // List and delete subscriptions first (they reference topics)
            try {
                String subListUrl = pubsubBase + "/v1/projects/" + projectId + "/subscriptions";
                String subResp = httpGet(subListUrl);
                Map<String, Object> subParsed = jsonMapper.readValue(subResp, Map.class);
                List<String> subscriptions = (List<String>) subParsed.get("subscriptions");
                if (subscriptions != null) {
                    for (String sub : subscriptions) {
                        try {
                            httpDelete(pubsubBase + "/v1/" + sub);
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete Pub/Sub subscription {}: {}", sub, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to list Pub/Sub subscriptions: {}", e.getMessage());
            }

            // List and delete topics
            try {
                String topicListUrl = pubsubBase + "/v1/projects/" + projectId + "/topics";
                String topicResp = httpGet(topicListUrl);
                Map<String, Object> topicParsed = jsonMapper.readValue(topicResp, Map.class);
                List<String> topics = (List<String>) topicParsed.get("topics");
                if (topics != null) {
                    for (String topic : topics) {
                        try {
                            httpDelete(pubsubBase + "/v1/" + topic);
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete Pub/Sub topic {}: {}", topic, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to list Pub/Sub topics: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Pub/Sub: {}", e.getMessage());
        }
        logger.info("Reset Pub/Sub: deleted {} resources", count);
        return count;
    }

    private int resetFirestore(String projectId) {
        int count = 0;
        String firestoreBase = "http://localhost:" + firestorePort;
        try {
            // The Firestore emulator supports a reset endpoint
            String resetUrl = firestoreBase + "/emulator/v1/projects/" + projectId + "/databases/(default)/documents";
            httpDelete(resetUrl);
            count++;
            logger.info("Reset Firestore: cleared all documents");
        } catch (Exception e) {
            logger.warn("Failed to reset Firestore: {}", e.getMessage());
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int resetBigQuery(String projectId) {
        int count = 0;
        try {
            // List datasets
            String dsListUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
            String dsResp = httpGet(dsListUrl);
            Map<String, Object> dsParsed = jsonMapper.readValue(dsResp, Map.class);
            List<Map<String, Object>> datasets = (List<Map<String, Object>>) dsParsed.get("datasets");
            if (datasets != null) {
                for (Map<String, Object> ds : datasets) {
                    Map<String, Object> ref = (Map<String, Object>) ds.get("datasetReference");
                    if (ref != null) {
                        String datasetId = (String) ref.get("datasetId");
                        try {
                            // deleteContents=true removes all tables in the dataset
                            httpDelete(bigqueryBase + "/bigquery/v2/projects/" + projectId
                                    + "/datasets/" + datasetId + "?deleteContents=true");
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete BigQuery dataset {}: {}", datasetId, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset BigQuery: {}", e.getMessage());
        }
        logger.info("Reset BigQuery: deleted {} datasets", count);
        return count;
    }

    @SuppressWarnings("unchecked")
    private int resetSpanner(String projectId) {
        int count = 0;
        String spannerBase = "http://localhost:" + spannerRestPort;
        try {
            // List instances
            String instanceListUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
            String instanceResp = httpGet(instanceListUrl);
            Map<String, Object> instanceParsed = jsonMapper.readValue(instanceResp, Map.class);
            List<Map<String, Object>> instances = (List<Map<String, Object>>) instanceParsed.get("instances");
            if (instances != null) {
                for (Map<String, Object> instance : instances) {
                    String instanceName = (String) instance.get("name");
                    if (instanceName != null) {
                        // List databases in this instance
                        try {
                            String dbListUrl = spannerBase + "/v1/" + instanceName + "/databases";
                            String dbResp = httpGet(dbListUrl);
                            Map<String, Object> dbParsed = jsonMapper.readValue(dbResp, Map.class);
                            List<Map<String, Object>> databases = (List<Map<String, Object>>) dbParsed.get("databases");
                            if (databases != null) {
                                for (Map<String, Object> db : databases) {
                                    String dbName = (String) db.get("name");
                                    if (dbName != null) {
                                        try {
                                            httpDelete(spannerBase + "/v1/" + dbName);
                                            count++;
                                        } catch (Exception e) {
                                            logger.debug("Failed to delete Spanner database {}: {}", dbName, e.getMessage());
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to list Spanner databases for {}: {}", instanceName, e.getMessage());
                        }
                        // Delete the instance
                        try {
                            httpDelete(spannerBase + "/v1/" + instanceName);
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete Spanner instance {}: {}", instanceName, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Spanner: {}", e.getMessage());
        }
        logger.info("Reset Spanner: deleted {} resources", count);
        return count;
    }

    private int resetSecretManager(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("DELETE FROM secret_versions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM secrets WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Secret Manager: {}", e.getMessage());
        }
        logger.info("Reset Secret Manager: deleted {} rows", count);
        return count;
    }

    private int resetCloudTasks(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            // Delete tasks first (FK dependency on task_queues)
            try (var ps = conn.prepareStatement("DELETE FROM cloud_tasks WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM task_queues WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Cloud Tasks: {}", e.getMessage());
        }
        logger.info("Reset Cloud Tasks: deleted {} rows", count);
        return count;
    }

    private int resetLogging(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM log_entries WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset Logging: {}", e.getMessage());
        }
        logger.info("Reset Logging: deleted {} rows", count);
        return count;
    }

    private int resetMonitoring(String projectId) {
        int count = 0;
        String projectName = "projects/" + projectId;
        try (var conn = dataSource.getConnection()) {
            // Delete metric points for series belonging to this project
            try (var ps = conn.prepareStatement(
                    "DELETE FROM metric_points WHERE series_id IN " +
                    "(SELECT id FROM time_series WHERE project_name = ?)")) {
                ps.setString(1, projectName);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM time_series WHERE project_name = ?")) {
                ps.setString(1, projectName);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Monitoring: {}", e.getMessage());
        }
        logger.info("Reset Monitoring: deleted {} rows", count);
        return count;
    }

    private int resetMemorystore(String projectId) {
        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 6379;
        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            int flushed = 0;
            for (int db = 0; db < 16; db++) {
                jedis.select(db);
                long size = jedis.dbSize();
                if (size > 0) {
                    jedis.flushDB();
                    flushed++;
                }
            }
            logger.info("Reset Memorystore: flushed {} database(s)", flushed);
            return flushed;
        } catch (Exception e) {
            logger.warn("Failed to reset Memorystore: {}", e.getMessage());
            return 0;
        }
    }

    private int resetBigtable(String projectId) {
        int count = 0;
        try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
            count = client.resetProject(projectId);
        } catch (Exception e) {
            logger.warn("Failed to reset Bigtable: {}", e.getMessage());
        }
        logger.info("Reset Bigtable: deleted {} instance(s)", count);
        return count;
    }

    private int resetCompute(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM compute_instances WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset Compute Engine: {}", e.getMessage());
        }
        logger.info("Reset Compute Engine: deleted {} rows", count);
        return count;
    }

    private int resetCloudRun(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("DELETE FROM cloudrun_revisions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM cloudrun_services WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Cloud Run: {}", e.getMessage());
        }
        logger.info("Reset Cloud Run: deleted {} rows", count);
        return count;
    }

    private int resetGke(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM gke_clusters WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset GKE: {}", e.getMessage());
        }
        logger.info("Reset GKE: deleted {} rows", count);
        return count;
    }

    // ========== Seed processors (call external emulator REST APIs) ==========

    @SuppressWarnings("unchecked")
    private int seedGcs(Object gcsData) {
        if (!(gcsData instanceof Map)) return 0;
        Map<String, Object> gcs = (Map<String, Object>) gcsData;
        int count = 0;
        String projectId = config.getProjectId();

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) gcs.get("buckets");
        if (buckets != null) {
            for (Map<String, Object> bucket : buckets) {
                try {
                    String name = (String) bucket.get("name");
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("name", name);
                    if (bucket.containsKey("location")) body.put("location", bucket.get("location"));
                    if (bucket.containsKey("storage_class")) body.put("storageClass", bucket.get("storage_class"));

                    String url = gcsBase + "/storage/v1/b?project=" + projectId;
                    try {
                        httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                        count++;
                        logger.debug("Seeded GCS bucket: {}", name);
                    } catch (Exception bucketErr) {
                        // 409 = bucket already exists, that's fine — continue to upload objects
                        if (!bucketErr.getMessage().contains("409")) {
                            throw bucketErr;
                        }
                        logger.debug("GCS bucket already exists: {}", name);
                    }
                    // Track bucket→project ownership for project-level isolation
                    registerBucketOwnership(name, projectId);

                    // Upload objects nested in this bucket
                    List<Map<String, Object>> bucketObjects = (List<Map<String, Object>>) bucket.get("objects");
                    if (bucketObjects != null) {
                        for (Map<String, Object> obj : bucketObjects) {
                            try {
                                String key = (String) obj.get("key");
                                String content = (String) obj.getOrDefault("content", "");
                                String contentType = (String) obj.getOrDefault("contentType", "application/octet-stream");

                                String objUrl = gcsBase + "/upload/storage/v1/b/" + name
                                        + "/o?name=" + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8)
                                        + "&uploadType=media";
                                try {
                                    httpPost(objUrl, content, contentType);
                                    count++;
                                    logger.debug("Seeded GCS object: {}/{}", name, key);
                                } catch (Exception objErr) {
                                    // Object may already exist — overwrite by ignoring 409
                                    if (objErr.getMessage() != null && objErr.getMessage().contains("409")) {
                                        count++;
                                        logger.debug("GCS object already exists, skipped: {}/{}", name, key);
                                    } else {
                                        logger.warn("Failed to seed GCS object {}/{}: {}", name, obj.get("key"), objErr.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to seed GCS object {}/{}: {}", name, obj.get("key"), e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed GCS bucket: {}", e.getMessage());
                }
            }
        }

        // Seed objects if present
        List<Map<String, Object>> objects = (List<Map<String, Object>>) gcs.get("objects");
        if (objects != null) {
            for (Map<String, Object> obj : objects) {
                try {
                    String bucketName = (String) obj.get("bucket");
                    String key = (String) obj.get("name");
                    String content = (String) obj.getOrDefault("content", "");

                    String url = gcsBase + "/upload/storage/v1/b/" + bucketName
                            + "/o?name=" + key + "&uploadType=media";
                    httpPost(url, content, "application/octet-stream");
                    count++;
                    logger.debug("Seeded GCS object: {}/{}", bucketName, key);
                } catch (Exception e) {
                    logger.warn("Failed to seed GCS object: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int seedPubSub(Object pubsubData) {
        if (!(pubsubData instanceof Map)) return 0;
        Map<String, Object> pubsub = (Map<String, Object>) pubsubData;
        int count = 0;

        List<Map<String, Object>> topics = (List<Map<String, Object>>) pubsub.get("topics");
        if (topics != null) {
            for (Map<String, Object> topic : topics) {
                try {
                    String project = (String) topic.getOrDefault("project", config.getProjectId());
                    String name = (String) topic.get("name");

                    String url = pubsubBase + "/v1/projects/" + project + "/topics/" + name;
                    boolean topicCreated = false;
                    try {
                        httpPut(url, "{}");
                        topicCreated = true;
                        count++;
                        logger.debug("Seeded Pub/Sub topic: {}", name);
                    } catch (Exception topicErr) {
                        if (topicErr.getMessage() != null && topicErr.getMessage().contains("409")) {
                            logger.debug("Pub/Sub topic already exists: {}", name);
                        } else {
                            logger.warn("Failed to seed Pub/Sub topic: {}", topicErr.getMessage());
                        }
                    }

                    // Create nested subscriptions for this topic
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> nestedSubs = (List<Map<String, Object>>) topic.get("subscriptions");
                    if (nestedSubs != null) {
                        for (Map<String, Object> sub : nestedSubs) {
                            try {
                                String subName = (String) sub.get("name");
                                int ackDeadline = sub.containsKey("ackDeadlineSeconds")
                                        ? ((Number) sub.get("ackDeadlineSeconds")).intValue()
                                        : sub.containsKey("ack_deadline_seconds")
                                        ? ((Number) sub.get("ack_deadline_seconds")).intValue() : 10;

                                Map<String, Object> subBody = new LinkedHashMap<>();
                                subBody.put("topic", "projects/" + project + "/topics/" + name);
                                subBody.put("ackDeadlineSeconds", ackDeadline);

                                String subUrl = pubsubBase + "/v1/projects/" + project + "/subscriptions/" + subName;
                                try {
                                    httpPut(subUrl, jsonMapper.writeValueAsString(subBody));
                                    count++;
                                    logger.debug("Seeded Pub/Sub subscription: {} (nested under topic {})", subName, name);
                                } catch (Exception subErr) {
                                    if (subErr.getMessage() != null && subErr.getMessage().contains("409")) {
                                        logger.debug("Pub/Sub subscription already exists: {}", subName);
                                    } else {
                                        logger.warn("Failed to seed Pub/Sub subscription {}: {}", subName, subErr.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to seed nested Pub/Sub subscription: {}", e.getMessage());
                            }
                        }
                    }

                    // Only publish seed messages for newly created topics
                    if (topicCreated) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> messages = (List<Map<String, Object>>) topic.get("messages");
                        if (messages != null) {
                            for (Map<String, Object> msg : messages) {
                                try {
                                    String data = (String) msg.get("data");
                                    if (data != null) {
                                        String encodedData = java.util.Base64.getEncoder().encodeToString(
                                                data.getBytes(StandardCharsets.UTF_8));

                                        Map<String, Object> pubMsg = new LinkedHashMap<>();
                                        pubMsg.put("data", encodedData);
                                        Object attrs = msg.get("attributes");
                                        if (attrs != null) {
                                            pubMsg.put("attributes", attrs);
                                        }

                                        Map<String, Object> publishBody = Map.of("messages", List.of(pubMsg));
                                        String publishUrl = pubsubBase + "/v1/projects/" + project + "/topics/" + name + ":publish";
                                        httpPost(publishUrl, jsonMapper.writeValueAsString(publishBody), "application/json");
                                        count++;
                                    }
                                } catch (Exception me) {
                                    logger.warn("Failed to publish Pub/Sub message to {}: {}", name, me.getMessage());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed Pub/Sub topic: {}", e.getMessage());
                }
            }
        }

        List<Map<String, Object>> subscriptions = (List<Map<String, Object>>) pubsub.get("subscriptions");
        if (subscriptions != null) {
            for (Map<String, Object> sub : subscriptions) {
                try {
                    String project = (String) sub.getOrDefault("project", config.getProjectId());
                    String name = (String) sub.get("name");
                    String topic = (String) sub.get("topic");
                    int ackDeadline = sub.containsKey("ack_deadline_seconds")
                            ? ((Number) sub.get("ack_deadline_seconds")).intValue() : 10;

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("topic", topic);
                    body.put("ackDeadlineSeconds", ackDeadline);

                    String url = pubsubBase + "/v1/projects/" + project + "/subscriptions/" + name;
                    try {
                        httpPut(url, jsonMapper.writeValueAsString(body));
                        count++;
                        logger.debug("Seeded Pub/Sub subscription: {}", name);
                    } catch (Exception subErr) {
                        if (subErr.getMessage() != null && subErr.getMessage().contains("409")) {
                            logger.debug("Pub/Sub subscription already exists: {}", name);
                        } else {
                            logger.warn("Failed to seed Pub/Sub subscription {}: {}", name, subErr.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed Pub/Sub subscription: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int seedBigQuery(Object bqData) {
        if (!(bqData instanceof Map)) return 0;
        Map<String, Object> bq = (Map<String, Object>) bqData;
        int count = 0;
        String projectId = config.getProjectId();

        List<Map<String, Object>> datasets = (List<Map<String, Object>>) bq.get("datasets");
        if (datasets != null) {
            for (Map<String, Object> dataset : datasets) {
                try {
                    String name = (String) dataset.get("name");
                    Map<String, Object> datasetRef = new LinkedHashMap<>();
                    datasetRef.put("datasetId", name);
                    datasetRef.put("projectId", projectId);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("datasetReference", datasetRef);
                    if (dataset.containsKey("location")) body.put("location", dataset.get("location"));

                    String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
                    try {
                        httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                        count++;
                        logger.debug("Seeded BigQuery dataset: {}", name);
                    } catch (Exception dsErr) {
                        if (dsErr.getMessage() != null && dsErr.getMessage().contains("409")) {
                            logger.debug("BigQuery dataset already exists: {}", name);
                            count++;
                        } else if (dsErr.getMessage() != null && dsErr.getMessage().contains("UNIQUE constraint")) {
                            // Emulator has stale SQLite state — reset and retry
                            logger.info("BigQuery stale state detected for dataset {}, resetting", name);
                            try {
                                httpDelete(bigqueryBase + "/bigquery/v2/projects/" + projectId
                                        + "/datasets/" + name + "?deleteContents=true");
                            } catch (Exception ignored) {}
                            try {
                                httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                                count++;
                            } catch (Exception retryErr) {
                                logger.warn("Failed to seed BigQuery dataset {} after reset: {}", name, retryErr.getMessage());
                            }
                        } else {
                            logger.warn("Failed to seed BigQuery dataset {}: {}", name, dsErr.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed BigQuery dataset: {}", e.getMessage());
                }
            }
        }

        List<Map<String, Object>> tables = (List<Map<String, Object>>) bq.get("tables");
        if (tables != null) {
            for (Map<String, Object> table : tables) {
                try {
                    String datasetName = (String) table.get("dataset");
                    String tableName = (String) table.get("name");

                    Map<String, Object> tableRef = new LinkedHashMap<>();
                    tableRef.put("projectId", projectId);
                    tableRef.put("datasetId", datasetName);
                    tableRef.put("tableId", tableName);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("tableReference", tableRef);
                    if (table.containsKey("schema")) body.put("schema", table.get("schema"));

                    String url = bigqueryBase + "/bigquery/v2/projects/" + projectId
                            + "/datasets/" + datasetName + "/tables";
                    try {
                        httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                        count++;
                        logger.debug("Seeded BigQuery table: {}.{}", datasetName, tableName);
                    } catch (Exception tblErr) {
                        if (tblErr.getMessage() != null &&
                                (tblErr.getMessage().contains("409") || tblErr.getMessage().contains("UNIQUE constraint"))) {
                            logger.debug("BigQuery table already exists: {}.{}", datasetName, tableName);
                            count++;
                        } else {
                            logger.warn("Failed to seed BigQuery table {}.{}: {}", datasetName, tableName, tblErr.getMessage());
                        }
                    }

                    // Insert rows if present
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) table.get("rows");
                    if (rows != null && !rows.isEmpty()) {
                        try {
                            List<Map<String, Object>> insertRows = new ArrayList<>();
                            int idx = 0;
                            for (Map<String, Object> row : rows) {
                                Map<String, Object> insertRow = new LinkedHashMap<>();
                                insertRow.put("insertId", "seed-" + tableName + "-" + idx++);
                                insertRow.put("json", row);
                                insertRows.add(insertRow);
                            }
                            Map<String, Object> insertBody = new LinkedHashMap<>();
                            insertBody.put("rows", insertRows);

                            String insertUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId
                                    + "/datasets/" + datasetName + "/tables/" + tableName + "/insertAll";
                            httpPost(insertUrl, jsonMapper.writeValueAsString(insertBody), "application/json");
                            count += rows.size();
                            logger.debug("Seeded {} rows into {}.{}", rows.size(), datasetName, tableName);
                        } catch (Exception e) {
                            logger.warn("Failed to insert rows into {}.{}: {}", datasetName, tableName, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed BigQuery table: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int seedSecretManager(Object smData) {
        if (!(smData instanceof Map)) return 0;
        Map<String, Object> sm = (Map<String, Object>) smData;
        int count = 0;
        String projectId = config.getProjectId();

        List<Map<String, Object>> secrets = (List<Map<String, Object>>) sm.get("secrets");
        if (secrets != null) {
            for (Map<String, Object> secret : secrets) {
                try {
                    String secretId = (String) secret.get("name");

                    // Insert secret directly into PostgreSQL
                    try (var conn = dataSource.getConnection();
                         var ps = conn.prepareStatement(
                                 "INSERT INTO secrets (project_id, secret_id, labels) VALUES (?, ?, '{}') " +
                                 "ON CONFLICT (project_id, secret_id) DO NOTHING")) {
                        ps.setString(1, projectId);
                        ps.setString(2, secretId);
                        ps.executeUpdate();
                    }

                    // Add versions if present
                    List<Map<String, Object>> versions = (List<Map<String, Object>>) secret.get("versions");
                    if (versions != null) {
                        int versionNum = 1;
                        for (Map<String, Object> ver : versions) {
                            String data = (String) ver.get("data");
                            String state = (String) ver.getOrDefault("state", "ENABLED");
                            if (data != null) {
                                try (var conn = dataSource.getConnection();
                                     var ps = conn.prepareStatement(
                                             "INSERT INTO secret_versions (project_id, secret_id, version_number, payload, state) " +
                                             "VALUES (?, ?, ?, ?, ?) ON CONFLICT (project_id, secret_id, version_number) DO NOTHING")) {
                                    ps.setString(1, projectId);
                                    ps.setString(2, secretId);
                                    ps.setInt(3, versionNum++);
                                    ps.setBytes(4, data.getBytes(StandardCharsets.UTF_8));
                                    ps.setString(5, state);
                                    ps.executeUpdate();
                                }
                            }
                        }
                    } else {
                        // Legacy format: single "value" field
                        String value = (String) secret.get("value");
                        if (value != null) {
                            try (var conn = dataSource.getConnection();
                                 var ps = conn.prepareStatement(
                                         "INSERT INTO secret_versions (project_id, secret_id, version_number, payload, state) " +
                                         "VALUES (?, ?, 1, ?, 'ENABLED') ON CONFLICT (project_id, secret_id, version_number) DO NOTHING")) {
                                ps.setString(1, projectId);
                                ps.setString(2, secretId);
                                ps.setBytes(3, value.getBytes(StandardCharsets.UTF_8));
                                ps.executeUpdate();
                            }
                        }
                    }

                    count++;
                    logger.debug("Seeded Secret Manager secret: {}", secretId);
                } catch (Exception e) {
                    logger.warn("Failed to seed secret: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    // ========== Memorystore seed ==========

    @SuppressWarnings("unchecked")
    private int seedMemorystore(Object msData) {
        if (!(msData instanceof Map)) return 0;
        Map<String, Object> ms = (Map<String, Object>) msData;
        int count = 0;
        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 6379;

        // Support optional 'database' field (default: 0)
        int dbIndex = ms.containsKey("database") ? ((Number) ms.get("database")).intValue() : 0;

        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            jedis.select(dbIndex);
            Pipeline pipe = jedis.pipelined();

            // Seed string keys
            List<Map<String, Object>> keys = (List<Map<String, Object>>) ms.get("keys");
            if (keys != null) {
                for (Map<String, Object> entry : keys) {
                    String key = (String) entry.get("key");
                    String value = String.valueOf(entry.get("value"));
                    if (key != null && value != null) {
                        pipe.set(key, value);
                        if (entry.containsKey("ttl")) {
                            pipe.expire(key, ((Number) entry.get("ttl")).longValue());
                        }
                        count++;
                    }
                }
            }

            // Seed hashes
            List<Map<String, Object>> hashes = (List<Map<String, Object>>) ms.get("hashes");
            if (hashes != null) {
                for (Map<String, Object> entry : hashes) {
                    String key = (String) entry.get("key");
                    Map<String, Object> fields = (Map<String, Object>) entry.get("fields");
                    if (key != null && fields != null) {
                        Map<String, String> stringFields = new LinkedHashMap<>();
                        fields.forEach((k, v) -> stringFields.put(k, String.valueOf(v)));
                        pipe.hset(key, stringFields);
                        count++;
                    }
                }
            }

            // Seed lists
            List<Map<String, Object>> lists = (List<Map<String, Object>>) ms.get("lists");
            if (lists != null) {
                for (Map<String, Object> entry : lists) {
                    String key = (String) entry.get("key");
                    List<String> values = (List<String>) entry.get("values");
                    if (values == null) values = (List<String>) entry.get("items"); // seed.yaml compat
                    if (key != null && values != null && !values.isEmpty()) {
                        pipe.rpush(key, values.toArray(new String[0]));
                        count++;
                    }
                }
            }

            // Seed sets
            List<Map<String, Object>> sets = (List<Map<String, Object>>) ms.get("sets");
            if (sets != null) {
                for (Map<String, Object> entry : sets) {
                    String key = (String) entry.get("key");
                    List<String> members = (List<String>) entry.get("members");
                    if (key != null && members != null && !members.isEmpty()) {
                        pipe.sadd(key, members.toArray(new String[0]));
                        count++;
                    }
                }
            }

            // Seed sorted sets
            List<Map<String, Object>> sortedSets = (List<Map<String, Object>>) ms.get("sorted_sets");
            if (sortedSets == null) sortedSets = (List<Map<String, Object>>) ms.get("zsets");
            if (sortedSets != null) {
                for (Map<String, Object> entry : sortedSets) {
                    String key = (String) entry.get("key");
                    List<Map<String, Object>> members = (List<Map<String, Object>>) entry.get("members");
                    if (key != null && members != null && !members.isEmpty()) {
                        for (Map<String, Object> m : members) {
                            String member = String.valueOf(m.get("member"));
                            double score = ((Number) m.get("score")).doubleValue();
                            pipe.zadd(key, score, member);
                        }
                        count++;
                    }
                }
            }

            pipe.sync();
        } catch (Exception e) {
            logger.warn("Failed to seed Memorystore via Valkey: {}", e.getMessage());
        }
        return count;
    }

    // ========== Spanner seed ==========

    @SuppressWarnings("unchecked")
    private int seedSpanner(Object spannerData) {
        if (!(spannerData instanceof Map)) return 0;
        Map<String, Object> spanner = (Map<String, Object>) spannerData;

        List<Map<String, Object>> instances = (List<Map<String, Object>>) spanner.get("instances");
        if (instances == null || instances.isEmpty()) {
            return 0;
        }

        int count = 0;
        String projectId = config.getProjectId();
        String spannerBase = "http://localhost:" + spannerRestPort;

        // Wait for Spanner emulator to be ready (it starts slower than the gateway)
        boolean spannerReady = false;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                String checkUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
                httpPostAndReturn(checkUrl.replace("/instances", "/instances"), "{}", "application/json");
                spannerReady = true;
                break;
            } catch (Exception e) {
                // Also try a simple GET to check if the REST port is up
                try {
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                            .uri(URI.create(spannerBase + "/v1/projects/" + projectId + "/instances"))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build();
                    java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() < 500) {
                        spannerReady = true;
                        break;
                    }
                } catch (Exception ignored) {}
                logger.info("Waiting for Spanner emulator to be ready (attempt {}/15)...", attempt + 1);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
            }
        }
        if (!spannerReady) {
            logger.warn("Spanner emulator not ready after 15s, skipping seed");
            return 0;
        }

        instances = (List<Map<String, Object>>) spanner.get("instances");
        if (instances != null) {
            for (Map<String, Object> instance : instances) {
                try {
                    String instanceName = (String) instance.get("name");

                    // Create instance (ignore 409 if already exists)
                    Map<String, Object> instanceConfig = new LinkedHashMap<>();
                    instanceConfig.put("config", "emulator-config");
                    instanceConfig.put("displayName", instanceName);
                    instanceConfig.put("nodeCount", 1);

                    Map<String, Object> createBody = new LinkedHashMap<>();
                    createBody.put("instanceId", instanceName);
                    createBody.put("instance", instanceConfig);

                    String instanceUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
                    try {
                        httpPost(instanceUrl, jsonMapper.writeValueAsString(createBody), "application/json");
                    } catch (Exception e) {
                        // 409 Conflict is expected if instance already exists
                        if (!e.getMessage().contains("409")) {
                            logger.warn("Failed to create Spanner instance {}: {}", instanceName, e.getMessage());
                        }
                    }
                    count++;
                    logger.debug("Seeded Spanner instance: {}", instanceName);

                    // Create databases for this instance
                    List<Map<String, Object>> databases = (List<Map<String, Object>>) instance.get("databases");
                    if (databases != null) {
                        for (Map<String, Object> db : databases) {
                            try {
                                String dbName = (String) db.get("name");
                                List<String> ddlStatements = (List<String>) db.get("ddl");

                                Map<String, Object> dbBody = new LinkedHashMap<>();
                                dbBody.put("createStatement", "CREATE DATABASE " + dbName);
                                if (ddlStatements != null && !ddlStatements.isEmpty()) {
                                    dbBody.put("extraStatements", ddlStatements);
                                }

                                String dbUrl = spannerBase + "/v1/projects/" + projectId
                                        + "/instances/" + instanceName + "/databases";
                                try {
                                    httpPost(dbUrl, jsonMapper.writeValueAsString(dbBody), "application/json");
                                } catch (Exception e) {
                                    if (!e.getMessage().contains("409")) {
                                        logger.warn("Failed to create Spanner database {}: {}", dbName, e.getMessage());
                                    }
                                }
                                count++;
                                logger.debug("Seeded Spanner database: {}/{}", instanceName, dbName);

                                // Insert row data if present
                                List<Map<String, Object>> dataEntries = (List<Map<String, Object>>) db.get("data");
                                if (dataEntries != null && !dataEntries.isEmpty()) {
                                    count += seedSpannerRows(spannerBase, projectId, instanceName, dbName, dataEntries);
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to seed Spanner database: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed Spanner instance: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * Insert rows into Spanner tables via the REST commit API.
     * Uses insertOrUpdate mutations for idempotent re-seeding.
     */
    @SuppressWarnings("unchecked")
    private int seedSpannerRows(String spannerBase, String projectId, String instanceName,
                                String dbName, List<Map<String, Object>> dataEntries) {
        int count = 0;
        String sessionName = null;
        String dbPath = "projects/" + projectId + "/instances/" + instanceName + "/databases/" + dbName;

        try {
            // 1. Create a session
            String sessionUrl = spannerBase + "/v1/" + dbPath + "/sessions";
            String sessionResp = httpPostAndReturn(sessionUrl, "{}", "application/json");
            Map<String, Object> sessionObj = jsonMapper.readValue(sessionResp, Map.class);
            sessionName = (String) sessionObj.get("name");

            if (sessionName == null) {
                logger.warn("Failed to create Spanner session for {}/{}", instanceName, dbName);
                return 0;
            }

            // 2. Build mutations from data entries
            List<Map<String, Object>> mutations = new ArrayList<>();
            for (Map<String, Object> entry : dataEntries) {
                String table = (String) entry.get("table");
                List<String> columns = (List<String>) entry.get("columns");
                List<List<String>> rows = (List<List<String>>) entry.get("rows");

                if (table != null && columns != null && rows != null) {
                    // Convert rows to list of string lists for Spanner values format
                    List<List<String>> values = new ArrayList<>();
                    for (List<String> row : rows) {
                        List<String> rowValues = new ArrayList<>();
                        for (Object val : row) {
                            rowValues.add(val != null ? String.valueOf(val) : null);
                        }
                        values.add(rowValues);
                    }

                    Map<String, Object> insertOrUpdate = new LinkedHashMap<>();
                    insertOrUpdate.put("table", table);
                    insertOrUpdate.put("columns", columns);
                    insertOrUpdate.put("values", values);

                    Map<String, Object> mutation = new LinkedHashMap<>();
                    mutation.put("insertOrUpdate", insertOrUpdate);
                    mutations.add(mutation);

                    count += rows.size();
                }
            }

            if (!mutations.isEmpty()) {
                // 3. Commit the mutations
                Map<String, Object> commitBody = new LinkedHashMap<>();
                Map<String, Object> txn = new LinkedHashMap<>();
                txn.put("readWrite", Map.of());
                commitBody.put("singleUseTransaction", txn);
                commitBody.put("mutations", mutations);

                String commitUrl = spannerBase + "/v1/" + sessionName + ":commit";
                httpPost(commitUrl, jsonMapper.writeValueAsString(commitBody), "application/json");
                logger.debug("Seeded {} rows into Spanner {}/{}", count, instanceName, dbName);
            }
        } catch (Exception e) {
            logger.warn("Failed to seed Spanner rows for {}/{}: {}", instanceName, dbName, e.getMessage());
        } finally {
            // 4. Delete the session
            if (sessionName != null) {
                try {
                    httpDelete(spannerBase + "/v1/" + sessionName);
                } catch (Exception e) {
                    logger.debug("Failed to delete Spanner session: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    // ========== Firestore seed ==========

    @SuppressWarnings("unchecked")
    private int seedFirestore(Object fsData) {
        if (!(fsData instanceof Map)) return 0;
        Map<String, Object> fs = (Map<String, Object>) fsData;

        List<Map<String, Object>> collections = (List<Map<String, Object>>) fs.get("collections");
        if (collections == null || collections.isEmpty()) {
            return 0;
        }

        int count = 0;
        String projectId = config.getProjectId();
        String firestoreBase = "http://localhost:" + firestorePort;

        // Wait for Firestore emulator to be ready (it starts slower than the gateway)
        boolean firestoreReady = false;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                String checkUrl = firestoreBase + "/v1/projects/" + projectId + "/databases/(default)/documents";
                httpGet(checkUrl);
                firestoreReady = true;
                break;
            } catch (Exception e) {
                logger.info("Waiting for Firestore emulator to be ready (attempt {}/15)...", attempt + 1);
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
            }
        }
        if (!firestoreReady) {
            logger.warn("Firestore emulator not ready after retries, skipping seed");
            return 0;
        }

        collections = (List<Map<String, Object>>) fs.get("collections");
        if (collections != null) {
            for (Map<String, Object> coll : collections) {
                String collName = (String) coll.get("name");
                List<Map<String, Object>> documents = (List<Map<String, Object>>) coll.get("documents");
                if (documents != null) {
                    for (Map<String, Object> doc : documents) {
                        try {
                            String docId = (String) doc.get("id");
                            Map<String, Object> fields = (Map<String, Object>) doc.get("fields");
                            if (docId != null && fields != null) {
                                // Convert fields to Firestore value format
                                Map<String, Object> firestoreFields = new LinkedHashMap<>();
                                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                                    firestoreFields.put(entry.getKey(), toFirestoreValue(entry.getValue()));
                                }
                                Map<String, Object> body = Map.of("fields", firestoreFields);

                                String url = firestoreBase + "/v1/projects/" + projectId
                                    + "/databases/(default)/documents/" + collName + "/" + docId;
                                // Use PATCH to create or update (upsert) with retry for emulator startup
                                String bodyJson = jsonMapper.writeValueAsString(body);
                                int maxRetries = 3;
                                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                                    try {
                                        httpPatch(url, bodyJson);
                                        break;
                                    } catch (Exception retryEx) {
                                        if (attempt == maxRetries) throw retryEx;
                                        logger.debug("Firestore seed attempt {}/{} failed, retrying: {}", attempt, maxRetries, retryEx.getMessage());
                                        Thread.sleep(1000L * attempt);
                                    }
                                }
                                count++;
                                logger.debug("Seeded Firestore document: {}/{}", collName, docId);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to seed Firestore document: {}", e.getMessage());
                        }
                    }
                }
            }
        }
        return count;
    }

    private Object toFirestoreValue(Object value) {
        if (value == null) {
            Map<String, Object> nullMap = new LinkedHashMap<>();
            nullMap.put("nullValue", null);
            return nullMap;
        }
        if (value instanceof String) return Map.of("stringValue", value);
        if (value instanceof Integer || value instanceof Long) return Map.of("integerValue", String.valueOf(value));
        if (value instanceof Double || value instanceof Float) return Map.of("doubleValue", value);
        if (value instanceof Boolean) return Map.of("booleanValue", value);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            Map<String, Object> mapFields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                mapFields.put(entry.getKey(), toFirestoreValue(entry.getValue()));
            }
            return Map.of("mapValue", Map.of("fields", mapFields));
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            List<Object> values = new ArrayList<>();
            for (Object item : list) {
                values.add(toFirestoreValue(item));
            }
            return Map.of("arrayValue", Map.of("values", values));
        }
        return Map.of("stringValue", String.valueOf(value));
    }

    // ========== Bigtable seed ==========

    @SuppressWarnings("unchecked")
    private int seedBigtable(Object btData) {
        if (!(btData instanceof Map)) return 0;
        Map<String, Object> bt = (Map<String, Object>) btData;
        int count = 0;

        List<Map<String, Object>> instances = (List<Map<String, Object>>) bt.get("instances");
        if (instances != null) {
            for (Map<String, Object> instance : instances) {
                String instanceName = (String) instance.get("name");
                List<Map<String, Object>> tables = (List<Map<String, Object>>) instance.get("tables");
                if (tables != null) {
                    for (Map<String, Object> table : tables) {
                        try {
                            String tableName = (String) table.get("name");
                            List<String> columnFamilies = (List<String>) table.get("columnFamilies");
                            List<Map<String, Object>> rows = (List<Map<String, Object>>) table.get("rows");
                            if (columnFamilies == null || columnFamilies.isEmpty()) {
                                columnFamilies = List.of("cf1");
                            }

                            try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                                client.ensureTable(config.getProjectId(), instanceName, tableName, columnFamilies);
                            }

                            if (rows != null) {
                                for (Map<String, Object> row : rows) {
                                    String rowKey = (String) row.get("key");
                                    Map<String, Object> cells = (Map<String, Object>) row.get("cells");
                                    if (rowKey != null && cells != null) {
                                        try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                                            client.mutateRow(config.getProjectId(), instanceName, tableName, rowKey, cells);
                                        }
                                        count++;
                                    }
                                }
                            }
                            logger.debug("Seeded Bigtable table: {}/{} ({} rows)", instanceName, tableName, rows != null ? rows.size() : 0);
                        } catch (Exception e) {
                            logger.warn("Failed to seed Bigtable table: {}", e.getMessage());
                        }
                    }
                }
            }
        }
        return count;
    }

    // ========== Cloud Tasks seed ==========

    @SuppressWarnings("unchecked")
    private int seedCloudTasks(Object ctData) {
        if (!(ctData instanceof Map)) return 0;
        Map<String, Object> ct = (Map<String, Object>) ctData;
        int count = 0;
        String projectId = config.getProjectId();

        List<Map<String, Object>> queues = (List<Map<String, Object>>) ct.get("queues");
        if (queues != null) {
            for (Map<String, Object> queue : queues) {
                try {
                    String queueId = (String) queue.get("name");
                    String locationId = (String) queue.getOrDefault("location", "us-central1");
                    String state = (String) queue.getOrDefault("state", "RUNNING");
                    int maxAttempts = queue.containsKey("maxAttempts")
                        ? ((Number) queue.get("maxAttempts")).intValue()
                        : queue.containsKey("max_attempts")
                        ? ((Number) queue.get("max_attempts")).intValue() : 5;

                    try (var conn = dataSource.getConnection();
                         var ps = conn.prepareStatement(
                             "INSERT INTO task_queues (project_id, queue_id, location_id, state, max_attempts) " +
                             "VALUES (?, ?, ?, ?, ?) ON CONFLICT (project_id, queue_id) DO NOTHING")) {
                        ps.setString(1, projectId);
                        ps.setString(2, queueId);
                        ps.setString(3, locationId);
                        ps.setString(4, state);
                        ps.setInt(5, maxAttempts);
                        ps.executeUpdate();
                    }
                    count++;
                    logger.debug("Seeded Cloud Tasks queue: {}", queueId);
                } catch (Exception e) {
                    logger.warn("Failed to seed Cloud Tasks queue: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    // ========== Cloud Workflows seed ==========

    @SuppressWarnings("unchecked")
    private int seedWorkflows(Object wfData, String projectId) {
        if (!(wfData instanceof Map)) return 0;
        Map<String, Object> wf = (Map<String, Object>) wfData;
        int count = 0;

        List<Map<String, Object>> workflows = (List<Map<String, Object>>) wf.get("workflows");
        if (workflows != null) {
            for (Map<String, Object> entry : workflows) {
                try {
                    String name = (String) entry.get("name");
                    String location = (String) entry.getOrDefault("location", "us-central1");
                    String source = (String) entry.get("source");
                    if (name == null || source == null) {
                        logger.warn("Skipping workflow seed entry missing name or source: {}", entry);
                        continue;
                    }
                    // Validate YAML source
                    try {
                        yamlMapper.readValue(source, Map.class);
                    } catch (Exception e) {
                        logger.warn("Invalid YAML source for workflow '{}', skipping: {}", name, e.getMessage());
                        continue;
                    }
                    workflowsStore.upsertWorkflow(projectId, location, name, source);
                    count++;
                    logger.debug("Seeded workflow: {}/{}/{}", projectId, location, name);
                } catch (Exception e) {
                    logger.warn("Failed to seed workflow: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    private int resetWorkflows(String projectId) {
        try {
            workflowsStore.resetByProject(projectId);
            logger.info("Reset workflows for project {}", projectId);
            return 1;
        } catch (Exception e) {
            logger.warn("Failed to reset workflows: {}", e.getMessage());
            return 0;
        }
    }

    // ========== Cloud Scheduler seed/reset ==========

    private int seedCloudScheduler(Object schedulerData, String projectId) {
        if (schedulerEmulator == null) {
            logger.warn("Cloud Scheduler seed skipped — emulator is not registered");
            return 0;
        }
        Map<String, Object> scheduler = asMap(schedulerData);
        int count = 0;
        for (Map<String, Object> jobEntry : mapList(scheduler, "jobs")) {
            try {
                String locationId = stringOr(jobEntry, "us-central1", "location", "region");
                String jobId = idFromName(string(jobEntry, "job_id", "jobId", "name"), "jobs");
                String schedule = string(jobEntry, "schedule");
                if (jobId == null || schedule == null) {
                    logger.warn("Skipping Scheduler seed entry missing job_id/name or schedule: {}", jobEntry);
                    continue;
                }

                String parent = "projects/" + projectId + "/locations/" + locationId;
                com.google.cloud.scheduler.v1.Job.Builder job = com.google.cloud.scheduler.v1.Job.newBuilder()
                        .setName(parent + "/jobs/" + jobId)
                        .setSchedule(schedule)
                        .setTimeZone(stringOr(jobEntry, "UTC", "time_zone", "timeZone"));
                String description = string(jobEntry, "description");
                if (description != null) job.setDescription(description);

                if (!applySchedulerTarget(projectId, jobEntry, job)) {
                    logger.warn("Skipping Scheduler job {} without a supported target", jobId);
                    continue;
                }
                Map<String, Object> retryConfig = asMap(first(jobEntry, "retry_config", "retryConfig"));
                if (!retryConfig.isEmpty()) {
                    job.setRetryConfig(buildSchedulerRetryConfig(retryConfig));
                }

                boolean created = this.<com.google.cloud.scheduler.v1.Job>callGrpcIgnoringAlreadyExists(observer ->
                        schedulerEmulator.getServiceImpl().createJob(
                                com.google.cloud.scheduler.v1.CreateJobRequest.newBuilder()
                                        .setParent(parent)
                                        .setJob(job.build())
                                        .build(), observer));
                if (created) count++;
            } catch (Exception e) {
                logger.warn("Failed to seed Scheduler job: {}", e.getMessage());
            }
        }
        return count;
    }

    private boolean applySchedulerTarget(String projectId, Map<String, Object> jobEntry,
                                         com.google.cloud.scheduler.v1.Job.Builder job) {
        Map<String, Object> httpTarget = asMap(first(jobEntry, "http_target", "httpTarget"));
        if (!httpTarget.isEmpty()) {
            String uri = string(httpTarget, "uri", "url");
            if (uri == null) return false;
            com.google.cloud.scheduler.v1.HttpTarget.Builder target =
                    com.google.cloud.scheduler.v1.HttpTarget.newBuilder()
                            .setUri(uri)
                            .setHttpMethod(parseSchedulerHttpMethod(stringOr(httpTarget, "GET", "http_method", "httpMethod", "method")))
                            .putAllHeaders(stringMap(first(httpTarget, "headers")));
            ByteString body = bytesFrom(httpTarget, "body", "body_base64", "bodyBase64");
            if (!body.isEmpty()) target.setBody(body);
            job.setHttpTarget(target);
            return true;
        }

        Map<String, Object> pubsubTarget = asMap(first(jobEntry, "pubsub_target", "pubsubTarget", "pubsub"));
        if (!pubsubTarget.isEmpty()) {
            String topic = normalizePubSubTopic(projectId, string(pubsubTarget, "topic_name", "topicName", "topic"));
            if (topic == null) return false;
            com.google.cloud.scheduler.v1.PubsubTarget.Builder target =
                    com.google.cloud.scheduler.v1.PubsubTarget.newBuilder()
                            .setTopicName(topic)
                            .putAllAttributes(stringMap(first(pubsubTarget, "attributes")));
            ByteString data = bytesFrom(pubsubTarget, "data", "data_base64", "dataBase64");
            if (!data.isEmpty()) target.setData(data);
            job.setPubsubTarget(target);
            return true;
        }

        Map<String, Object> appEngineTarget = asMap(first(jobEntry, "app_engine_http_target", "appEngineHttpTarget"));
        if (!appEngineTarget.isEmpty()) {
            com.google.cloud.scheduler.v1.AppEngineHttpTarget.Builder target =
                    com.google.cloud.scheduler.v1.AppEngineHttpTarget.newBuilder()
                            .setRelativeUri(stringOr(appEngineTarget, "/", "relative_uri", "relativeUri"))
                            .setHttpMethod(parseSchedulerHttpMethod(stringOr(appEngineTarget, "GET", "http_method", "httpMethod", "method")))
                            .putAllHeaders(stringMap(first(appEngineTarget, "headers")));
            ByteString body = bytesFrom(appEngineTarget, "body", "body_base64", "bodyBase64");
            if (!body.isEmpty()) target.setBody(body);
            job.setAppEngineHttpTarget(target);
            return true;
        }
        return false;
    }

    private com.google.cloud.scheduler.v1.RetryConfig buildSchedulerRetryConfig(Map<String, Object> retryConfig) {
        com.google.cloud.scheduler.v1.RetryConfig.Builder builder =
                com.google.cloud.scheduler.v1.RetryConfig.newBuilder();
        if (first(retryConfig, "retry_count", "retryCount") != null) {
            builder.setRetryCount(intValue(retryConfig, 0, "retry_count", "retryCount"));
        }
        setDuration(builder::setMinBackoffDuration, retryConfig, "min_backoff_duration_seconds", "minBackoffDurationSeconds");
        setDuration(builder::setMaxBackoffDuration, retryConfig, "max_backoff_duration_seconds", "maxBackoffDurationSeconds");
        setDuration(builder::setMaxRetryDuration, retryConfig, "max_retry_duration_seconds", "maxRetryDurationSeconds");
        if (first(retryConfig, "max_doublings", "maxDoublings") != null) {
            builder.setMaxDoublings(intValue(retryConfig, 0, "max_doublings", "maxDoublings"));
        }
        return builder.build();
    }

    private int resetCloudScheduler(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            List<String> jobNames = new ArrayList<>();
            try (var ps = conn.prepareStatement(
                    "SELECT location_id, job_id FROM scheduler_jobs WHERE project_id = ?")) {
                ps.setString(1, projectId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        jobNames.add("projects/" + projectId + "/locations/" + rs.getString(1) + "/jobs/" + rs.getString(2));
                    }
                }
            }
            if (schedulerEmulator != null) {
                for (String jobName : jobNames) {
                    try {
                        this.<com.google.protobuf.Empty>callGrpc(observer -> schedulerEmulator.getServiceImpl().deleteJob(
                                com.google.cloud.scheduler.v1.DeleteJobRequest.newBuilder().setName(jobName).build(), observer));
                        count++;
                    } catch (Exception e) {
                        logger.debug("Failed to delete Scheduler job {} via service: {}", jobName, e.getMessage());
                    }
                }
            }
            try (var ps = conn.prepareStatement("DELETE FROM scheduler_jobs WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM scheduler_executions WHERE job_name LIKE ?")) {
                ps.setString(1, "projects/" + projectId + "/locations/%/jobs/%");
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Cloud Scheduler: {}", e.getMessage());
        }
        logger.info("Reset Cloud Scheduler: deleted {} resources", count);
        return count;
    }

    // ========== Cloud Functions seed/reset ==========

    private int seedCloudFunctions(Object functionsData, String projectId) {
        if (functionsEmulator == null) {
            logger.warn("Cloud Functions seed skipped — emulator is not registered");
            return 0;
        }
        Map<String, Object> functionsRoot = asMap(functionsData);
        int count = 0;
        for (Map<String, Object> fnEntry : mapList(functionsRoot, "functions")) {
            try {
                String locationId = stringOr(fnEntry, "us-central1", "location", "region");
                String functionId = idFromName(string(fnEntry, "function_id", "functionId", "name"), "functions");
                Map<String, Object> buildConfigMap = asMap(first(fnEntry, "build_config", "buildConfig"));
                String runtime = string(fnEntry, "runtime");
                if (runtime == null) runtime = string(buildConfigMap, "runtime");
                String entryPoint = string(fnEntry, "entry_point", "entryPoint");
                if (entryPoint == null) entryPoint = string(buildConfigMap, "entry_point", "entryPoint");
                if (functionId == null || runtime == null || entryPoint == null) {
                    logger.warn("Skipping Cloud Functions seed entry missing function_id/name, runtime, or entry_point: {}", fnEntry);
                    continue;
                }

                com.google.cloud.functions.v2.BuildConfig.Builder buildConfig =
                        com.google.cloud.functions.v2.BuildConfig.newBuilder()
                                .setRuntime(runtime)
                                .setEntryPoint(entryPoint)
                                .putAllEnvironmentVariables(stringMap(first(buildConfigMap, "environment_variables", "environmentVariables", "env")));
                applyFunctionSource(buildConfig, first(fnEntry, "source", "source_uri", "sourceUri"));
                applyFunctionSource(buildConfig, first(buildConfigMap, "source"));

                com.google.cloud.functions.v2.Function.Builder function =
                        com.google.cloud.functions.v2.Function.newBuilder()
                                .setBuildConfig(buildConfig)
                                .setServiceConfig(buildFunctionServiceConfig(fnEntry));

                Map<String, Object> eventTrigger = asMap(first(fnEntry, "event_trigger", "eventTrigger", "trigger"));
                if (!eventTrigger.isEmpty()) {
                    function.setEventTrigger(buildFunctionEventTrigger(projectId, locationId, eventTrigger));
                }

                String parent = "projects/" + projectId + "/locations/" + locationId;
                boolean created = this.<com.google.longrunning.Operation>callGrpcIgnoringAlreadyExists(observer ->
                        functionsEmulator.getServiceImpl().createFunction(
                                com.google.cloud.functions.v2.CreateFunctionRequest.newBuilder()
                                        .setParent(parent)
                                        .setFunctionId(functionId)
                                        .setFunction(function.build())
                                        .build(), observer));
                if (created) count++;
            } catch (Exception e) {
                logger.warn("Failed to seed Cloud Function: {}", e.getMessage());
            }
        }
        return count;
    }

    private com.google.cloud.functions.v2.ServiceConfig buildFunctionServiceConfig(Map<String, Object> fnEntry) {
        Map<String, Object> serviceConfigMap = asMap(first(fnEntry, "service_config", "serviceConfig"));
        com.google.cloud.functions.v2.ServiceConfig.Builder serviceConfig =
                com.google.cloud.functions.v2.ServiceConfig.newBuilder()
                        .putAllEnvironmentVariables(stringMap(first(serviceConfigMap, "environment_variables", "environmentVariables", "env")));
        String service = string(serviceConfigMap, "service");
        if (service == null) service = string(fnEntry, "service");
        if (service != null) serviceConfig.setService(service);
        String uri = string(serviceConfigMap, "uri", "endpoint");
        if (uri == null) uri = string(fnEntry, "uri", "endpoint");
        if (uri != null) serviceConfig.setUri(uri);
        String memory = string(serviceConfigMap, "available_memory", "availableMemory", "memory");
        if (memory != null) serviceConfig.setAvailableMemory(memory);
        String cpu = string(serviceConfigMap, "available_cpu", "availableCpu", "cpu");
        if (cpu != null) serviceConfig.setAvailableCpu(cpu);
        if (first(serviceConfigMap, "timeout_seconds", "timeoutSeconds") != null) {
            serviceConfig.setTimeoutSeconds(intValue(serviceConfigMap, 60, "timeout_seconds", "timeoutSeconds"));
        }
        if (first(serviceConfigMap, "min_instance_count", "minInstanceCount") != null) {
            serviceConfig.setMinInstanceCount(intValue(serviceConfigMap, 0, "min_instance_count", "minInstanceCount"));
        }
        if (first(serviceConfigMap, "max_instance_count", "maxInstanceCount") != null) {
            serviceConfig.setMaxInstanceCount(intValue(serviceConfigMap, 0, "max_instance_count", "maxInstanceCount"));
        }
        return serviceConfig.build();
    }

    private com.google.cloud.functions.v2.EventTrigger buildFunctionEventTrigger(
            String projectId, String locationId, Map<String, Object> trigger) {
        com.google.cloud.functions.v2.EventTrigger.Builder builder =
                com.google.cloud.functions.v2.EventTrigger.newBuilder()
                        .setTriggerRegion(stringOr(trigger, locationId, "trigger_region", "triggerRegion", "region"));
        String topic = string(trigger, "pubsub_topic", "pubsubTopic", "topic");
        if (topic != null) {
            builder.setPubsubTopic(normalizePubSubTopic(projectId, topic));
            builder.setEventType(stringOr(trigger, "google.cloud.pubsub.topic.v1.messagePublished", "event_type", "eventType"));
        } else {
            String eventType = string(trigger, "event_type", "eventType");
            if (eventType != null) builder.setEventType(eventType);
        }
        String serviceAccount = string(trigger, "service_account_email", "serviceAccountEmail");
        if (serviceAccount != null) builder.setServiceAccountEmail(serviceAccount);
        for (Map<String, Object> filter : mapList(trigger, "event_filters", "eventFilters", "filters")) {
            String attribute = string(filter, "attribute");
            String value = string(filter, "value");
            if (attribute == null || value == null) continue;
            com.google.cloud.functions.v2.EventFilter.Builder eventFilter =
                    com.google.cloud.functions.v2.EventFilter.newBuilder()
                            .setAttribute(attribute)
                            .setValue(value);
            String operator = string(filter, "operator");
            if (operator != null) eventFilter.setOperator(operator);
            builder.addEventFilters(eventFilter);
        }
        return builder.build();
    }

    private void applyFunctionSource(com.google.cloud.functions.v2.BuildConfig.Builder buildConfig, Object sourceObj) {
        if (sourceObj == null) return;
        if (sourceObj instanceof String source) {
            if (source.startsWith("gs://")) {
                String rest = source.substring("gs://".length());
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    buildConfig.setSource(com.google.cloud.functions.v2.Source.newBuilder()
                            .setStorageSource(com.google.cloud.functions.v2.StorageSource.newBuilder()
                                    .setBucket(rest.substring(0, slash))
                                    .setObject(rest.substring(slash + 1))));
                }
            } else {
                buildConfig.setSource(com.google.cloud.functions.v2.Source.newBuilder().setGitUri(source));
            }
            return;
        }
        Map<String, Object> source = asMap(sourceObj);
        String gitUri = string(source, "git_uri", "gitUri");
        if (gitUri != null) {
            buildConfig.setSource(com.google.cloud.functions.v2.Source.newBuilder().setGitUri(gitUri));
            return;
        }
        String bucket = string(source, "bucket");
        String object = string(source, "object", "name");
        String uploadUrl = string(source, "source_upload_url", "sourceUploadUrl", "upload_url", "uploadUrl");
        if (bucket != null || uploadUrl != null) {
            com.google.cloud.functions.v2.StorageSource.Builder storage =
                    com.google.cloud.functions.v2.StorageSource.newBuilder();
            if (bucket != null) storage.setBucket(bucket);
            if (object != null) storage.setObject(object);
            if (uploadUrl != null) storage.setSourceUploadUrl(uploadUrl);
            if (first(source, "generation") != null) {
                storage.setGeneration(longValue(source, 0, "generation"));
            }
            buildConfig.setSource(com.google.cloud.functions.v2.Source.newBuilder().setStorageSource(storage));
        }
    }

    private int resetCloudFunctions(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            List<String> functionNames = new ArrayList<>();
            try (var ps = conn.prepareStatement(
                    "SELECT location_id, function_id FROM cloud_functions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        functionNames.add("projects/" + projectId + "/locations/" + rs.getString(1) + "/functions/" + rs.getString(2));
                    }
                }
            }
            if (functionsEmulator != null) {
                for (String functionName : functionNames) {
                    try {
                        this.<com.google.longrunning.Operation>callGrpc(observer -> functionsEmulator.getServiceImpl().deleteFunction(
                                com.google.cloud.functions.v2.DeleteFunctionRequest.newBuilder().setName(functionName).build(), observer));
                        count++;
                    } catch (Exception e) {
                        logger.debug("Failed to delete Cloud Function {} via service: {}", functionName, e.getMessage());
                    }
                }
            }
            try (var ps = conn.prepareStatement("DELETE FROM cloud_functions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Cloud Functions: {}", e.getMessage());
        }
        logger.info("Reset Cloud Functions: deleted {} resources", count);
        return count;
    }

    // ========== AlloyDB seed/reset ==========

    private int seedAlloyDB(Object alloyData, String projectId) {
        Map<String, Object> alloy = asMap(alloyData);
        int count = 0;
        for (Map<String, Object> cluster : mapList(alloy, "clusters")) {
            String locationId = stringOr(cluster, "us-central1", "location", "region");
            String clusterId = idFromName(string(cluster, "cluster_id", "clusterId", "name"), "clusters");
            if (clusterId == null) {
                logger.warn("Skipping AlloyDB cluster seed entry missing cluster_id/name: {}", cluster);
                continue;
            }
            if (seedAlloyDBCluster(projectId, locationId, clusterId)) count++;
            for (Map<String, Object> instance : mapList(cluster, "instances")) {
                if (seedAlloyDBInstance(projectId, locationId, clusterId, instance)) count++;
            }
            for (Map<String, Object> database : mapList(cluster, "databases")) {
                String databaseName = idFromName(string(database, "database", "database_name", "databaseName", "name"), "databases");
                if (databaseName != null && seedAlloyDBDatabase(projectId, locationId, clusterId, databaseName)) count++;
                for (Map<String, Object> table : mapList(database, "tables")) {
                    if (seedAlloyDBTable(projectId, locationId, clusterId, databaseName, table)) count++;
                }
            }
            for (Map<String, Object> table : mapList(cluster, "tables")) {
                if (seedAlloyDBTable(projectId, locationId, clusterId, table)) count++;
            }
            for (Map<String, Object> backup : mapList(cluster, "backups")) {
                if (seedAlloyDBBackup(projectId, locationId, clusterId, backup)) count++;
            }
            for (Map<String, Object> user : mapList(cluster, "users")) {
                if (seedAlloyDBUser(projectId, locationId, clusterId, user)) count++;
            }
        }
        for (Map<String, Object> instance : mapList(alloy, "instances")) {
            String locationId = stringOr(instance, "us-central1", "location", "region");
            String clusterId = idFromName(string(instance, "cluster_id", "clusterId", "cluster", "parent"), "clusters");
            if (clusterId != null && seedAlloyDBInstance(projectId, locationId, clusterId, instance)) count++;
        }
        for (Map<String, Object> backup : mapList(alloy, "backups")) {
            String locationId = stringOr(backup, "us-central1", "location", "region");
            String clusterId = idFromName(string(backup, "cluster_id", "clusterId", "cluster", "cluster_name", "clusterName"), "clusters");
            if (clusterId != null && seedAlloyDBBackup(projectId, locationId, clusterId, backup)) count++;
        }
        for (Map<String, Object> user : mapList(alloy, "users")) {
            String locationId = stringOr(user, "us-central1", "location", "region");
            String clusterId = idFromName(string(user, "cluster_id", "clusterId", "cluster", "parent"), "clusters");
            if (clusterId != null && seedAlloyDBUser(projectId, locationId, clusterId, user)) count++;
        }
        return count;
    }

    private boolean seedAlloyDBCluster(String projectId, String locationId, String clusterId) {
        String parent = "projects/" + projectId + "/locations/" + locationId;
        try {
            if (alloyDBEmulator != null) {
                return this.<com.google.longrunning.Operation>callGrpcIgnoringAlreadyExists(observer ->
                        alloyDBEmulator.getServiceImpl().createCluster(
                                com.google.cloud.alloydb.v1.CreateClusterRequest.newBuilder()
                                        .setParent(parent)
                                        .setClusterId(clusterId)
                                        .setCluster(com.google.cloud.alloydb.v1.Cluster.newBuilder().build())
                                        .build(), observer));
            }
            String fullName = parent + "/clusters/" + clusterId;
            String databaseName = com.localcloud.emulators.common.GrpcSupport.safeDatabaseName(clusterId);
            var now = java.time.Instant.now();
            var cluster = com.google.cloud.alloydb.v1.Cluster.newBuilder()
                    .setName(fullName)
                    .setState(com.google.cloud.alloydb.v1.Cluster.State.READY)
                    .setCreateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(now))
                    .setUpdateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(now))
                    .build();
            int inserted;
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("""
                         INSERT INTO alloydb_clusters
                         (project_id, location_id, cluster_id, database_name, cluster_proto)
                         VALUES (?, ?, ?, ?, ?)
                         ON CONFLICT (project_id, location_id, cluster_id) DO NOTHING
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, databaseName);
                ps.setBytes(5, cluster.toByteArray());
                inserted = ps.executeUpdate();
            }
            createAlloyDBPhysicalDatabase(databaseName);
            seedAlloyDBDatabase(projectId, locationId, clusterId, databaseName);
            return inserted > 0;
        } catch (Exception e) {
            logger.warn("Failed to seed AlloyDB cluster {}: {}", clusterId, e.getMessage());
            return false;
        }
    }

    private boolean seedAlloyDBInstance(String projectId, String locationId, String clusterId, Map<String, Object> instance) {
        String instanceId = idFromName(string(instance, "instance_id", "instanceId", "name"), "instances");
        if (instanceId == null) return false;
        String parent = "projects/" + projectId + "/locations/" + locationId + "/clusters/" + clusterId;
        try {
            if (alloyDBEmulator != null) {
                return this.<com.google.longrunning.Operation>callGrpcIgnoringAlreadyExists(observer ->
                        alloyDBEmulator.getServiceImpl().createInstance(
                                com.google.cloud.alloydb.v1.CreateInstanceRequest.newBuilder()
                                        .setParent(parent)
                                        .setInstanceId(instanceId)
                                        .setInstance(com.google.cloud.alloydb.v1.Instance.newBuilder().build())
                                        .build(), observer));
            }
            var now = java.time.Instant.now();
            var seededInstance = com.google.cloud.alloydb.v1.Instance.newBuilder()
                    .setName(parent + "/instances/" + instanceId)
                    .setState(com.google.cloud.alloydb.v1.Instance.State.READY)
                    .setCreateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(now))
                    .setUpdateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(now))
                    .build();
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("""
                         INSERT INTO alloydb_instances
                         (project_id, location_id, cluster_id, instance_id, instance_proto)
                         VALUES (?, ?, ?, ?, ?)
                         ON CONFLICT (project_id, location_id, cluster_id, instance_id) DO NOTHING
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, instanceId);
                ps.setBytes(5, seededInstance.toByteArray());
                return ps.executeUpdate() > 0;
            }
        } catch (Exception e) {
            logger.warn("Failed to seed AlloyDB instance {}: {}", instanceId, e.getMessage());
            return false;
        }
    }

    private boolean seedAlloyDBDatabase(String projectId, String locationId, String clusterId, String databaseName) {
        String physicalName = com.localcloud.emulators.common.GrpcSupport.safeDatabaseName(clusterId).equals(databaseName)
                ? databaseName
                : com.localcloud.emulators.common.GrpcSupport.safeDatabaseName(clusterId + "_" + databaseName);
        try (var conn = dataSource.getConnection()) {
            try (var find = conn.prepareStatement("""
                    SELECT 1 FROM alloydb_databases
                    WHERE project_id = ? AND location_id = ? AND cluster_id = ? AND database_name = ?
                    """)) {
                find.setString(1, projectId);
                find.setString(2, locationId);
                find.setString(3, clusterId);
                find.setString(4, databaseName);
                try (var rs = find.executeQuery()) {
                    if (rs.next()) {
                        createAlloyDBPhysicalDatabase(physicalName);
                        return false;
                    }
                }
            }
            try (var ps = conn.prepareStatement("""
                     INSERT INTO alloydb_databases
                     (project_id, location_id, cluster_id, database_name, physical_name)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, databaseName);
                ps.setString(5, physicalName);
                int inserted = ps.executeUpdate();
                createAlloyDBPhysicalDatabase(physicalName);
                return inserted > 0;
            }
        } catch (Exception e) {
            logger.warn("Failed to seed AlloyDB database {}: {}", databaseName, e.getMessage());
            return false;
        }
    }

    private boolean seedAlloyDBTable(String projectId, String locationId, String clusterId, Map<String, Object> table) {
        return seedAlloyDBTable(projectId, locationId, clusterId, null, table);
    }

    private boolean seedAlloyDBTable(String projectId, String locationId, String clusterId, String logicalDatabaseName,
                                     Map<String, Object> table) {
        String tableName = idFromName(string(table, "table", "table_name", "tableName", "name"), "tables");
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            logger.warn("Skipping AlloyDB table seed entry with invalid table name: {}", table);
            return false;
        }
        String databaseName = findAlloyDBDatabaseName(projectId, locationId, clusterId, logicalDatabaseName);
        if (databaseName == null) return false;

        List<Map<String, Object>> rows = mapList(table, "rows");
        List<Map<String, Object>> columns = mapList(table, "columns");
        if (columns.isEmpty()) {
            columns = inferAlloyDBColumns(rows);
        }
        if (columns.isEmpty()) {
            logger.warn("Skipping AlloyDB table {} with no columns or rows", tableName);
            return false;
        }

        try (var conn = dataSource.getConnection(databaseName);
             var stmt = conn.createStatement()) {
            String ddl = "CREATE TABLE IF NOT EXISTS " + quoteIdentifier(tableName) + " (" +
                    columns.stream()
                            .map(c -> quoteIdentifier(string(c, "name")) + " " + alloyDBColumnType(string(c, "type")))
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("") + ")";
            stmt.execute(ddl);
            if (!rows.isEmpty()) {
                insertAlloyDBRows(conn, tableName, columns, rows);
            }
            return true;
        } catch (Exception e) {
            logger.warn("Failed to seed AlloyDB table {}.{}: {}", databaseName, tableName, e.getMessage());
            return false;
        }
    }

    private String findAlloyDBDatabaseName(String projectId, String locationId, String clusterId) {
        return findAlloyDBDatabaseName(projectId, locationId, clusterId, null);
    }

    private String findAlloyDBDatabaseName(String projectId, String locationId, String clusterId, String logicalDatabaseName) {
        if (logicalDatabaseName != null) {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("""
                         SELECT physical_name FROM alloydb_databases
                         WHERE project_id = ? AND location_id = ? AND cluster_id = ? AND database_name = ?
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, logicalDatabaseName);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve AlloyDB database {} for cluster {}: {}", logicalDatabaseName, clusterId, e.getMessage());
                return null;
            }
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT physical_name FROM alloydb_databases
                     WHERE project_id = ? AND location_id = ? AND cluster_id = ?
                     ORDER BY database_name
                     LIMIT 1
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, clusterId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve AlloyDB database for cluster {}: {}", clusterId, e.getMessage());
            return null;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT database_name FROM alloydb_clusters
                     WHERE project_id = ? AND location_id = ? AND cluster_id = ?
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, clusterId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve AlloyDB fallback database for cluster {}: {}", clusterId, e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> inferAlloyDBColumns(List<Map<String, Object>> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) names.addAll(row.keySet());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String name : names) {
            Object sample = null;
            for (Map<String, Object> row : rows) {
                if (row.get(name) != null) {
                    sample = row.get(name);
                    break;
                }
            }
            columns.add(Map.of("name", name, "type", inferAlloyDBType(sample)));
        }
        return columns;
    }

    private String inferAlloyDBType(Object value) {
        if (value instanceof Integer || value instanceof Long) return "BIGINT";
        if (value instanceof Number) return "NUMERIC";
        if (value instanceof Boolean) return "BOOLEAN";
        return "TEXT";
    }

    private String alloyDBColumnType(String type) {
        if (type == null || type.isBlank()) return "TEXT";
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("VARCHAR\\([0-9]{1,4}\\)")
                || normalized.matches("NUMERIC\\([0-9]{1,3},[0-9]{1,3}\\)")
                || normalized.equals("TEXT")
                || normalized.equals("INTEGER")
                || normalized.equals("BIGINT")
                || normalized.equals("NUMERIC")
                || normalized.equals("BOOLEAN")
                || normalized.equals("TIMESTAMP")
                || normalized.equals("DATE")
                || normalized.equals("DOUBLE PRECISION")
                || normalized.equals("REAL")) {
            return normalized;
        }
        return "TEXT";
    }

    private void insertAlloyDBRows(java.sql.Connection conn, String tableName, List<Map<String, Object>> columns,
                                   List<Map<String, Object>> rows) throws Exception {
        List<String> columnNames = columns.stream().map(c -> string(c, "name")).toList();
        String sql = "INSERT INTO " + quoteIdentifier(tableName) + " (" +
                columnNames.stream().map(this::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("") +
                ") VALUES (" + columnNames.stream().map(c -> "?").reduce((a, b) -> a + ", " + b).orElse("") + ")";
        try (var ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < columnNames.size(); i++) {
                    Object value = row.get(columnNames.get(i));
                    if (value instanceof Map || value instanceof List) {
                        ps.setString(i + 1, jsonMapper.writeValueAsString(value));
                    } else {
                        ps.setObject(i + 1, value);
                    }
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void createAlloyDBPhysicalDatabase(String databaseName) {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + quoteIdentifier(databaseName));
        } catch (Exception e) {
            logger.debug("AlloyDB database {} may already exist or cannot be created: {}", databaseName, e.getMessage());
        }
        try (var conn = dataSource.getConnection(databaseName); var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (Exception e) {
            logger.debug("AlloyDB database {} pgvector setup skipped: {}", databaseName, e.getMessage());
        }
    }

    private void dropAlloyDBPhysicalDatabase(String databaseName) {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(databaseName));
        } catch (Exception e) {
            logger.debug("AlloyDB database {} drop skipped: {}", databaseName, e.getMessage());
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean seedAlloyDBBackup(String projectId, String locationId, String clusterId, Map<String, Object> backup) {
        String backupId = idFromName(string(backup, "backup_id", "backupId", "name"), "backups");
        if (backupId == null) return false;
        String parent = "projects/" + projectId + "/locations/" + locationId;
        String clusterName = "projects/" + projectId + "/locations/" + locationId + "/clusters/" + clusterId;
        try {
            return this.<com.google.longrunning.Operation>callGrpcIgnoringAlreadyExists(observer ->
                    alloyDBEmulator.getServiceImpl().createBackup(
                            com.google.cloud.alloydb.v1.CreateBackupRequest.newBuilder()
                                    .setParent(parent)
                                    .setBackupId(backupId)
                                    .setBackup(com.google.cloud.alloydb.v1.Backup.newBuilder().setClusterName(clusterName).build())
                                    .build(), observer));
        } catch (Exception e) {
            logger.warn("Failed to seed AlloyDB backup {}: {}", backupId, e.getMessage());
            return false;
        }
    }

    private boolean seedAlloyDBUser(String projectId, String locationId, String clusterId, Map<String, Object> user) {
        String userId = idFromName(string(user, "user_id", "userId", "name"), "users");
        if (userId == null) return false;
        String parent = "projects/" + projectId + "/locations/" + locationId + "/clusters/" + clusterId;
        try {
            this.<com.google.cloud.alloydb.v1.User>callGrpc(observer -> alloyDBEmulator.getServiceImpl().createUser(
                    com.google.cloud.alloydb.v1.CreateUserRequest.newBuilder()
                            .setParent(parent)
                            .setUserId(userId)
                            .setUser(com.google.cloud.alloydb.v1.User.newBuilder().build())
                            .build(), observer));
            return true;
        } catch (Exception e) {
            if (Status.fromThrowable(e).getCode() != Status.Code.ALREADY_EXISTS) {
                logger.warn("Failed to seed AlloyDB user {}: {}", userId, e.getMessage());
            }
            return false;
        }
    }

    private int resetAlloyDB(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            List<String> clusterNames = new ArrayList<>();
            List<String> databaseNames = new ArrayList<>();
            try (var ps = conn.prepareStatement(
                    "SELECT DISTINCT c.location_id, c.cluster_id, COALESCE(d.physical_name, c.database_name) AS physical_name " +
                    "FROM alloydb_clusters c LEFT JOIN alloydb_databases d " +
                    "ON d.project_id = c.project_id AND d.location_id = c.location_id AND d.cluster_id = c.cluster_id " +
                    "WHERE c.project_id = ?")) {
                ps.setString(1, projectId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        clusterNames.add("projects/" + projectId + "/locations/" + rs.getString(1) + "/clusters/" + rs.getString(2));
                        databaseNames.add(rs.getString(3));
                    }
                }
            }
            if (alloyDBEmulator != null) {
                for (String clusterName : clusterNames) {
                    try {
                        this.<com.google.longrunning.Operation>callGrpc(observer -> alloyDBEmulator.getServiceImpl().deleteCluster(
                                com.google.cloud.alloydb.v1.DeleteClusterRequest.newBuilder().setName(clusterName).build(), observer));
                        count++;
                    } catch (Exception e) {
                        logger.debug("Failed to delete AlloyDB cluster {} via service: {}", clusterName, e.getMessage());
                    }
                }
            }
            if (alloyDBEmulator == null) {
                for (String databaseName : databaseNames) {
                    dropAlloyDBPhysicalDatabase(databaseName);
                }
            }
            try (var ps = conn.prepareStatement("DELETE FROM alloydb_backups WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM alloydb_clusters WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset AlloyDB: {}", e.getMessage());
        }
        logger.info("Reset AlloyDB: deleted {} resources", count);
        return count;
    }

    // ========== Dataproc seed/reset ==========

    private int seedDataproc(Object dataprocData, String projectId) {
        if (dataprocEmulator == null) {
            logger.warn("Dataproc seed skipped — emulator is not registered");
            return 0;
        }
        Map<String, Object> dataproc = asMap(dataprocData);
        int count = 0;
        for (Map<String, Object> cluster : mapList(dataproc, "clusters")) {
            String region = stringOr(cluster, "us-central1", "region", "location");
            String clusterName = idFromName(string(cluster, "cluster_name", "clusterName", "name"), "clusters");
            if (clusterName == null) {
                logger.warn("Skipping Dataproc cluster seed entry missing cluster_name/name: {}", cluster);
                continue;
            }
            if (seedDataprocCluster(projectId, region, clusterName)) count++;
            for (Map<String, Object> job : mapList(cluster, "jobs")) {
                if (seedDataprocJob(projectId, region, clusterName, job)) count++;
            }
        }
        for (Map<String, Object> job : mapList(dataproc, "jobs")) {
            String region = stringOr(job, "us-central1", "region", "location");
            String clusterName = idFromName(string(job, "cluster_name", "clusterName", "cluster"), "clusters");
            if (seedDataprocJob(projectId, region, clusterName, job)) count++;
        }
        return count;
    }

    private boolean seedDataprocCluster(String projectId, String region, String clusterName) {
        try {
            return this.<com.google.longrunning.Operation>callGrpcIgnoringAlreadyExists(observer ->
                    dataprocEmulator.getClusterService().createCluster(
                            com.google.cloud.dataproc.v1.CreateClusterRequest.newBuilder()
                                    .setProjectId(projectId)
                                    .setRegion(region)
                                    .setCluster(com.google.cloud.dataproc.v1.Cluster.newBuilder()
                                            .setClusterName(clusterName)
                                            .build())
                                    .build(), observer));
        } catch (Exception e) {
            logger.warn("Failed to seed Dataproc cluster {}: {}", clusterName, e.getMessage());
            return false;
        }
    }

    private boolean seedDataprocJob(String projectId, String region, String defaultClusterName,
                                    Map<String, Object> jobEntry) {
        String clusterName = defaultClusterName;
        if (clusterName == null) {
            clusterName = idFromName(string(jobEntry, "cluster_name", "clusterName", "cluster"), "clusters");
        }
        String jobId = idFromName(string(jobEntry, "job_id", "jobId", "name"), "jobs");
        if (clusterName == null || jobId == null) {
            logger.warn("Skipping Dataproc job seed entry missing cluster_name/cluster or job_id/name: {}", jobEntry);
            return false;
        }
        com.google.cloud.dataproc.v1.Job.Builder job = com.google.cloud.dataproc.v1.Job.newBuilder()
                .setReference(com.google.cloud.dataproc.v1.JobReference.newBuilder()
                        .setProjectId(projectId)
                        .setJobId(jobId))
                .setPlacement(com.google.cloud.dataproc.v1.JobPlacement.newBuilder()
                        .setClusterName(clusterName));
        if (!applyDataprocJobType(jobEntry, job)) {
            logger.warn("Skipping Dataproc job {} without spark_job, pyspark_job, or spark_sql_job", jobId);
            return false;
        }
        try {
            this.<com.google.cloud.dataproc.v1.Job>callGrpc(observer -> dataprocEmulator.getJobService().submitJob(
                    com.google.cloud.dataproc.v1.SubmitJobRequest.newBuilder()
                            .setProjectId(projectId)
                            .setRegion(region)
                            .setJob(job)
                            .build(), observer));
            return true;
        } catch (Exception e) {
            logger.warn("Failed to seed Dataproc job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    private boolean applyDataprocJobType(Map<String, Object> jobEntry, com.google.cloud.dataproc.v1.Job.Builder job) {
        Map<String, Object> sparkJob = asMap(first(jobEntry, "spark_job", "sparkJob"));
        if (!sparkJob.isEmpty()) {
            com.google.cloud.dataproc.v1.SparkJob.Builder spark =
                    com.google.cloud.dataproc.v1.SparkJob.newBuilder()
                            .addAllArgs(stringList(first(sparkJob, "args")))
                            .addAllJarFileUris(stringList(first(sparkJob, "jar_file_uris", "jarFileUris", "jars")))
                            .addAllFileUris(stringList(first(sparkJob, "file_uris", "fileUris", "files")))
                            .putAllProperties(stringMap(first(sparkJob, "properties")));
            String mainJar = string(sparkJob, "main_jar_file_uri", "mainJarFileUri", "main_jar");
            if (mainJar != null) spark.setMainJarFileUri(mainJar);
            String mainClass = string(sparkJob, "main_class", "mainClass");
            if (mainClass != null) spark.setMainClass(mainClass);
            job.setSparkJob(spark);
            return true;
        }
        Map<String, Object> pysparkJob = asMap(first(jobEntry, "pyspark_job", "pysparkJob", "py_spark_job", "pySparkJob"));
        if (!pysparkJob.isEmpty()) {
            String mainPython = string(pysparkJob, "main_python_file_uri", "mainPythonFileUri", "main_python_file");
            if (mainPython == null) return false;
            com.google.cloud.dataproc.v1.PySparkJob.Builder pyspark =
                    com.google.cloud.dataproc.v1.PySparkJob.newBuilder()
                            .setMainPythonFileUri(mainPython)
                            .addAllArgs(stringList(first(pysparkJob, "args")))
                            .addAllPythonFileUris(stringList(first(pysparkJob, "python_file_uris", "pythonFileUris", "python_files")))
                            .addAllJarFileUris(stringList(first(pysparkJob, "jar_file_uris", "jarFileUris", "jars")))
                            .addAllFileUris(stringList(first(pysparkJob, "file_uris", "fileUris", "files")))
                            .putAllProperties(stringMap(first(pysparkJob, "properties")));
            job.setPysparkJob(pyspark);
            return true;
        }
        Map<String, Object> sparkSqlJob = asMap(first(jobEntry, "spark_sql_job", "sparkSqlJob"));
        if (!sparkSqlJob.isEmpty()) {
            com.google.cloud.dataproc.v1.SparkSqlJob.Builder sparkSql =
                    com.google.cloud.dataproc.v1.SparkSqlJob.newBuilder()
                            .addAllJarFileUris(stringList(first(sparkSqlJob, "jar_file_uris", "jarFileUris", "jars")))
                            .putAllProperties(stringMap(first(sparkSqlJob, "properties")));
            String queryFile = string(sparkSqlJob, "query_file_uri", "queryFileUri", "query_file");
            if (queryFile != null) sparkSql.setQueryFileUri(queryFile);
            job.setSparkSqlJob(sparkSql);
            return queryFile != null;
        }
        return false;
    }

    private int resetDataproc(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("DELETE FROM dataproc_jobs WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM dataproc_clusters WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Dataproc: {}", e.getMessage());
        }
        logger.info("Reset Dataproc: deleted {} resources", count);
        return count;
    }

    // ========== Cloud IAM seed/reset ==========

    private int seedCloudIAM(Object iamData) {
        if (iamEmulator == null) {
            logger.warn("Cloud IAM seed skipped — emulator is not registered");
            return 0;
        }
        Map<String, Object> iam = asMap(iamData);
        int count = 0;
        for (Map<String, Object> policyEntry : mapList(iam, "policies")) {
            try {
                String resource = string(policyEntry, "resource");
                if (resource == null) {
                    logger.warn("Skipping IAM seed entry missing resource: {}", policyEntry);
                    continue;
                }
                com.google.iam.v1.Policy.Builder policy = com.google.iam.v1.Policy.newBuilder();
                for (Map<String, Object> binding : mapList(policyEntry, "bindings")) {
                    String role = string(binding, "role");
                    if (role == null) continue;
                    policy.addBindings(com.google.iam.v1.Binding.newBuilder()
                            .setRole(role)
                            .addAllMembers(stringList(first(binding, "members"))));
                }
                this.<com.google.iam.v1.Policy>callGrpc(observer -> iamEmulator.getServiceImpl().setIamPolicy(
                        com.google.iam.v1.SetIamPolicyRequest.newBuilder()
                                .setResource(resource)
                                .setPolicy(policy)
                                .build(), observer));
                count++;
            } catch (Exception e) {
                logger.warn("Failed to seed IAM policy: {}", e.getMessage());
            }
        }
        return count;
    }

    private int resetCloudIAM(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     DELETE FROM iam_policies
                     WHERE resource_type = 'projects' AND (resource_id = ? OR resource_id LIKE ?)
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, projectId + "/%");
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset Cloud IAM: {}", e.getMessage());
        }
        logger.info("Reset Cloud IAM: deleted {} resources", count);
        return count;
    }

    // ========== Seed parsing helpers ==========

    private static void addResult(Map<String, Object> results, String service, int count) {
        results.put(service, results.containsKey(service) ? ((int) results.get(service)) + count : count);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> typed = new LinkedHashMap<>();
        raw.forEach((key, val) -> typed.put(String.valueOf(key), val));
        return typed;
    }

    private static List<Map<String, Object>> mapList(Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> typed = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> itemMap = asMap(item);
            if (!itemMap.isEmpty()) typed.add(itemMap);
        }
        return typed;
    }

    private static Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private static String string(Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        if (value == null) return null;
        String str = String.valueOf(value);
        return str.isBlank() ? null : str;
    }

    private static String stringOr(Map<String, Object> map, String defaultValue, String... keys) {
        String value = string(map, keys);
        return value != null ? value : defaultValue;
    }

    private static int intValue(Map<String, Object> map, int defaultValue, String... keys) {
        Object value = first(map, keys);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static long longValue(Map<String, Object> map, long defaultValue, String... keys) {
        Object value = first(map, keys);
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            List<String> strings = new ArrayList<>();
            for (Object item : list) {
                if (item != null) strings.add(String.valueOf(item));
            }
            return strings;
        }
        String str = String.valueOf(value);
        return str.isBlank() ? List.of() : List.of(str);
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, String> strings = new LinkedHashMap<>();
        raw.forEach((key, val) -> {
            if (key != null && val != null) strings.put(String.valueOf(key), String.valueOf(val));
        });
        return strings;
    }

    private static String idFromName(String value, String collection) {
        if (value == null) return null;
        String marker = "/" + collection + "/";
        int markerIndex = value.indexOf(marker);
        if (markerIndex >= 0) return value.substring(markerIndex + marker.length());
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static ByteString bytesFrom(Map<String, Object> map, String plainKey, String... base64Keys) {
        Object encoded = first(map, base64Keys);
        if (encoded != null) {
            return ByteString.copyFrom(Base64.getDecoder().decode(String.valueOf(encoded)));
        }
        Object plain = first(map, plainKey);
        return plain == null ? ByteString.EMPTY : ByteString.copyFromUtf8(String.valueOf(plain));
    }

    private static String normalizePubSubTopic(String projectId, String topic) {
        if (topic == null) return null;
        return topic.startsWith("projects/") ? topic : "projects/" + projectId + "/topics/" + topic;
    }

    private static com.google.cloud.scheduler.v1.HttpMethod parseSchedulerHttpMethod(String method) {
        try {
            return com.google.cloud.scheduler.v1.HttpMethod.valueOf(method.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (Exception e) {
            return com.google.cloud.scheduler.v1.HttpMethod.GET;
        }
    }

    private static void setDuration(Consumer<com.google.protobuf.Duration> setter, Map<String, Object> map, String... keys) {
        Object value = first(map, keys);
        if (value == null) return;
        setter.accept(com.google.protobuf.Duration.newBuilder()
                .setSeconds(longValue(map, 0, keys))
                .build());
    }

    private <T> boolean callGrpcIgnoringAlreadyExists(Consumer<StreamObserver<T>> call) {
        try {
            callGrpc(call);
            return true;
        } catch (RuntimeException e) {
            if (Status.fromThrowable(e).getCode() == Status.Code.ALREADY_EXISTS) {
                return false;
            }
            throw e;
        }
    }

    private <T> T callGrpc(Consumer<StreamObserver<T>> call) {
        GrpcCaptureObserver<T> observer = new GrpcCaptureObserver<>();
        call.accept(observer);
        return observer.valueOrThrow();
    }

    private static final class GrpcCaptureObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }

        T valueOrThrow() {
            if (error != null) {
                if (error instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(error);
            }
            if (!completed) {
                throw new IllegalStateException("gRPC call did not complete");
            }
            return value;
        }
    }

    // ========== HTTP helpers ==========

    private void httpPut(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.warn("HTTP PUT {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP PUT %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
    }

    private void httpPatch(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.warn("HTTP PATCH {} failed ({}): {}", url, response.statusCode(), response.body());
            throw new RuntimeException(String.format("HTTP PATCH %s failed with status %d", url, response.statusCode()));
        }
    }

    private void httpPost(String url, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", contentType)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.warn("HTTP POST {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP POST %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
    }

    private String httpPostAndReturn(String url, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", contentType)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.warn("HTTP POST {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP POST %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
        return response.body();
    }

    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.debug("HTTP GET {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP GET %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
        return response.body();
    }

    private void httpDelete(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.debug("HTTP DELETE {} failed ({})", url, response.statusCode());
        }
    }

    /**
     * Register bucket→project ownership in PostgreSQL for project-level GCS isolation.
     */
    private void registerBucketOwnership(String bucketName, String projectId) {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO gcs_bucket_projects (bucket_name, project_id) VALUES (?, ?) " +
                 "ON CONFLICT (bucket_name) DO NOTHING")) {
            ps.setString(1, bucketName);
            ps.setString(2, projectId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.debug("Could not register GCS bucket ownership for {}: {}", bucketName, e.getMessage());
        }
    }

    /**
     * Close the underlying HTTP client to release resources.
     */
    public void close() {
        httpClient.close();
    }

    // ========== Response helpers ==========

    private com.linecorp.armeria.common.HttpResponse jsonResponse(HttpStatus status, Object body) {
        try {
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            return com.linecorp.armeria.common.HttpResponse.of(status, MediaType.JSON, json);
        } catch (Exception e) {
            return com.linecorp.armeria.common.HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8, "JSON serialization error");
        }
    }

    private com.linecorp.armeria.common.HttpResponse errorResponse(HttpStatus status, String message) {
        try {
            Map<String, Object> error = Map.of(
                    "error", true,
                    "message", message
            );
            return com.linecorp.armeria.common.HttpResponse.of(status,
                    MediaType.JSON, jsonMapper.writeValueAsString(error));
        } catch (Exception e) {
            return com.linecorp.armeria.common.HttpResponse.of(status,
                    MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
