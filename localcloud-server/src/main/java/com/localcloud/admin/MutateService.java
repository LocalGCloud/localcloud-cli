package com.localcloud.admin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.emulators.workflows.WorkflowsServiceImpl;
import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mutate service for the LocalCloud dashboard. Handles CRUD mutations for all
 * services through their emulator APIs. Registered at the
 * {@code /mutate} path prefix.
 */
public class MutateService {

    private static final Logger logger = LoggerFactory.getLogger(MutateService.class);

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final ServiceRegistry registry;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // Delegate for workflow execution (set after construction to break circular dependency)
    private WorkflowsServiceImpl workflowsService;

    // Base URLs computed from registry
    private final String gcsBase;
    private final String pubsubBase;
    private final String bigqueryBase;
    private final String spannerBase;
    private final int bigtablePort;
    private final String firestoreBase;

    public MutateService(LocalCloudConfig config, PostgresDataSource dataSource, ServiceRegistry registry) {
        this.config = config;
        this.dataSource = dataSource;
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();

        // Compute base URLs from registry definitions
        this.gcsBase = baseUrl(registry.getService("gcs"));
        this.pubsubBase = baseUrl(registry.getService("pubsub"));
        this.bigqueryBase = baseUrl(registry.getService("bigquery"));
        this.firestoreBase = baseUrl(registry.getService("firestore"));

        ServiceDefinition spannerDef = registry.getService("spanner");
        int spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;
        this.spannerBase = "http://localhost:" + spannerRestPort;

        ServiceDefinition bigtableDef = registry.getService("bigtable");
        this.bigtablePort = bigtableDef != null ? bigtableDef.port() : 8087;
    }

    public void setWorkflowsService(WorkflowsServiceImpl service) {
        this.workflowsService = service;
    }

    private static String baseUrl(ServiceDefinition def) {
        if (def == null) return "http://localhost:0";
        return "http://localhost:" + def.port();
    }

    /**
     * Extract the concise error from a verbose Spanner DDL error message.
     * The emulator returns messages like:
     *   "Error parsing Spanner DDL statement: CREATE TABLE ... (full DDL) ... : Syntax error on line 6, column 32: ..."
     * This extracts just the "Syntax error on line 6, column 32: ..." part.
     */
    static String extractDdlError(String message) {
        if (message == null) return "DDL operation failed";
        if (message.isBlank()) return "DDL operation failed";

        String knownIssue = knownDdlIssue(message);
        if (knownIssue != null) {
            return "DDL error: " + knownIssue;
        }

        // Look for "Syntax error on line" — the actual error is at the end
        int syntaxIdx = message.lastIndexOf("Syntax error on line");
        if (syntaxIdx > 0) {
            return "DDL " + message.substring(syntaxIdx);
        }

        // Look for other specific emulator error patterns at the end
        // e.g., "Duplicate name in schema: TableName."
        int dupIdx = message.lastIndexOf("Duplicate name in schema:");
        if (dupIdx > 0) {
            return "DDL error: " + message.substring(dupIdx);
        }

        // Avoid returning full DDL dumps when the emulator only reports its parser prefix.
        String prefix = "Error parsing Spanner DDL statement: ";
        if (message.startsWith(prefix)) {
            return genericDdlParseError();
        }

        // If the message is too long (contains full DDL dump), truncate it
        if (message.length() > 500) {
            // Find the last ": " which typically precedes the actual error
            int lastColon = message.lastIndexOf(": ");
            if (lastColon > 0 && lastColon < message.length() - 5) {
                String tail = message.substring(lastColon + 2).trim();
                String upperTail = tail.toUpperCase();
                if (!tail.isEmpty()
                        && tail.length() > 5
                        && !upperTail.startsWith("CREATE TABLE")
                        && !upperTail.startsWith("CREATE INDEX")
                        && !upperTail.startsWith("CREATE UNIQUE INDEX")) {
                    return "DDL error: " + tail;
                }
            }
            return genericDdlParseError();
        }

        return message;
    }

    private static String knownDdlIssue(String message) {
        String upper = message.toUpperCase();
        if (upper.contains("ARRAY<<")) {
            return "ARRAY types use one '<'. Use ARRAY<STRING(MAX)> instead of ARRAY<<STRING(20)>.";
        }
        if (upper.matches("(?s).*\\bARRAY\\s*<\\s*STRING\\s*\\(\\s*\\d+\\s*\\)\\s*>.*")) {
            return "Spanner array element STRING types should use STRING(MAX), for example ARRAY<STRING(MAX)>.";
        }
        if (upper.matches("(?s).*\\bCONSTRAINT\\s+\\S+\\s+PRIMARY\\s+KEY\\b.*")) {
            return "named PRIMARY KEY constraints are not supported here. Put PRIMARY KEY (...) after the CREATE TABLE column list.";
        }
        if (upper.contains("UNIQUE NONNULL")) {
            return "UNIQUE NONNULL is not Spanner DDL syntax. Use CREATE UNIQUE INDEX ... ON Table(Column) instead.";
        }
        return null;
    }

    private static String genericDdlParseError() {
        return "Error parsing DDL statement. Check your Spanner syntax. Common issues: ARRAY<STRING> should be ARRAY<STRING(MAX)>, "
                + "named CONSTRAINT PRIMARY KEY is not supported here, and UNIQUE NONNULL is not valid syntax.";
    }

    // ========== Dispatcher endpoints ==========

    @Post("/{service}/{operation}")
    public com.linecorp.armeria.common.HttpResponse mutate(@Param("service") String service,
                                                            @Param("operation") String operation,
                                                            AggregatedHttpRequest request) {
        try {
            String body = request.contentUtf8();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);

            String result = switch (service) {
                case "gcs" -> mutateGcs(operation, null, json);
                case "spanner" -> mutateSpanner(operation, null, json);
                case "bigquery" -> mutateBigQuery(operation, null, json);
                case "secretmanager" -> mutateSecretManager(operation, null, json);
                case "memorystore" -> mutateMemorystore(operation, null, json);
                case "firestore" -> mutateFirestore(operation, null, json);
                case "bigtable" -> mutateBigtable(operation, null, json);
                case "pubsub" -> mutatePubSub(operation, null, json);
                case "cloudtasks" -> mutateCloudTasks(operation, null, json);
                case "workflows" -> mutateWorkflows(operation, null, json);
                default -> mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Unknown service: " + service));
            };
            return com.linecorp.armeria.common.HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);
        } catch (Exception e) {
            logger.warn("Mutate error for {}/{}: {}", service, operation, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/{service}/{operation}/{subOp}")
    public com.linecorp.armeria.common.HttpResponse mutateWithSubOp(@Param("service") String service,
                                                                     @Param("operation") String operation,
                                                                     @Param("subOp") String subOp,
                                                                     AggregatedHttpRequest request) {
        try {
            String body = request.contentUtf8();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);

            String result = switch (service) {
                case "gcs" -> mutateGcs(operation, subOp, json);
                case "spanner" -> mutateSpanner(operation, subOp, json);
                case "bigquery" -> mutateBigQuery(operation, subOp, json);
                case "secretmanager" -> mutateSecretManager(operation, subOp, json);
                case "memorystore" -> mutateMemorystore(operation, subOp, json);
                case "firestore" -> mutateFirestore(operation, subOp, json);
                case "bigtable" -> mutateBigtable(operation, subOp, json);
                case "pubsub" -> mutatePubSub(operation, subOp, json);
                case "cloudtasks" -> mutateCloudTasks(operation, subOp, json);
                case "workflows" -> mutateWorkflows(operation, subOp, json);
                default -> mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Unknown service: " + service));
            };
            return com.linecorp.armeria.common.HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);
        } catch (Exception e) {
            logger.warn("Mutate error for {}/{}/{}: {}", service, operation, subOp, e.getMessage());
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ========== GCS ==========

    @SuppressWarnings("unchecked")
    private String mutateGcs(String operation, String subOp, Map<String, Object> json) throws Exception {
        if ("buckets".equals(operation) && subOp == null) {
            // Create bucket
            String bucketName = (String) json.get("name");
            String location = (String) json.getOrDefault("location", "US");

            Map<String, Object> bucketBody = new LinkedHashMap<>();
            bucketBody.put("name", bucketName);
            bucketBody.put("location", location);

            String projectId = config.getProjectId();
            String url = gcsBase + "/storage/v1/b?project=" + projectId;
            String response = httpPostAndReturn(url, mapper.writeValueAsString(bucketBody), "application/json");
            // Track bucket→project ownership for project-level isolation
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO gcs_bucket_projects (bucket_name, project_id) VALUES (?, ?) " +
                     "ON CONFLICT (bucket_name) DO NOTHING")) {
                ps.setString(1, bucketName);
                ps.setString(2, projectId);
                ps.executeUpdate();
            } catch (Exception e) {
                logger.debug("Could not register GCS bucket ownership: {}", e.getMessage());
            }
            logger.debug("Created GCS bucket: {}", bucketName);
            return response;
        }
        if ("objects".equals(operation) && subOp == null) {
            // Create/upload object
            String bucket = (String) json.get("bucket");
            String key = (String) json.get("key");
            String content = (String) json.getOrDefault("content", "");
            String contentType = (String) json.getOrDefault("contentType", "application/octet-stream");

            String url = gcsBase + "/upload/storage/v1/b/" + bucket
                    + "/o?name=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "&uploadType=media";
            String response = httpPostAndReturn(url, content, contentType);
            logger.debug("Created GCS object: {}/{}", bucket, key);
            return mapper.writeValueAsString(Map.of("status", "created", "bucket", bucket, "key", key));
        }
        if ("objects".equals(operation) && "delete".equals(subOp)) {
            // Delete object
            String bucket = (String) json.get("bucket");
            String key = (String) json.get("key");

            String url = gcsBase + "/storage/v1/b/" + bucket + "/o/"
                    + URLEncoder.encode(key, StandardCharsets.UTF_8);
            httpDelete(url);
            logger.debug("Deleted GCS object: {}/{}", bucket, key);
            return mapper.writeValueAsString(Map.of("status", "deleted", "bucket", bucket, "key", key));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid GCS operation: " + operation));
    }

    // ========== Spanner ==========

    @SuppressWarnings("unchecked")
    private String mutateSpanner(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = config.getProjectId();

        if ("rows".equals(operation) && subOp == null) {
            // Insert rows (insertOrUpdate mutation)
            return spannerCommitMutation(projectId, json, "insertOrUpdate");
        }
        if ("rows".equals(operation) && "update".equals(subOp)) {
            // Update rows (update mutation)
            return spannerCommitMutation(projectId, json, "update");
        }
        if ("rows".equals(operation) && "delete".equals(subOp)) {
            // Delete rows
            return spannerDeleteRows(projectId, json);
        }
        // Create Spanner instance
        if ("createInstance".equals(operation)) {
            String instanceId = (String) json.get("instance");
            if (instanceId == null) return mapper.writeValueAsString(Map.of("error", true, "message", "instance is required"));
            String displayName = (String) json.getOrDefault("displayName", instanceId);

            String url = spannerBase + "/v1/projects/" + projectId + "/instances";
            String payload = mapper.writeValueAsString(Map.of(
                "instanceId", instanceId,
                "instance", Map.of(
                    "config", "projects/" + projectId + "/instanceConfigs/emulator-config",
                    "displayName", displayName,
                    "nodeCount", 1
                )
            ));
            String result = httpPostAndReturn(url, payload, "application/json");
            return mapper.writeValueAsString(Map.of("status", "created", "instance", instanceId, "response", mapper.readValue(result, Object.class)));
        }

        // Create Spanner database
        if ("createDatabase".equals(operation)) {
            String instanceId = (String) json.get("instance");
            String databaseId = (String) json.get("database");
            if (instanceId == null || databaseId == null)
                return mapper.writeValueAsString(Map.of("error", true, "message", "instance and database are required"));

            logger.info("Creating Spanner database: instance={}, database={}, project={}", instanceId, databaseId, projectId);

            List<String> ddlStatements = json.containsKey("ddl") ? (List<String>) json.get("ddl") : List.of();

            String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instanceId + "/databases";
            logger.debug("Spanner createDatabase URL: {}", url);
            
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("createStatement", "CREATE DATABASE `" + databaseId + "`");
            if (!ddlStatements.isEmpty()) payload.put("extraStatements", ddlStatements);
            
            logger.debug("Spanner createDatabase payload: {}", payload);

            String result = httpPostAndReturn(url, mapper.writeValueAsString(payload), "application/json");
            return mapper.writeValueAsString(Map.of("status", "created", "database", databaseId, "response", mapper.readValue(result, Object.class)));
        }

        // Execute DDL (CREATE TABLE, DROP TABLE, ALTER TABLE)
        if ("ddl".equals(operation)) {
            String instanceId = (String) json.get("instance");
            String databaseId = (String) json.get("database");
            if (instanceId == null || databaseId == null)
                return mapper.writeValueAsString(Map.of("error", true, "message", "instance and database are required"));

            List<String> statements = (List<String>) json.get("statements");
            if (statements == null || statements.isEmpty())
                return mapper.writeValueAsString(Map.of("error", true, "message", "statements list is required"));

            String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instanceId
                    + "/databases/" + databaseId + "/ddl";
            String payload = mapper.writeValueAsString(Map.of("statements", statements));

            // Use PATCH for DDL updates
            HttpRequest patchRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<String> patchResponse = httpClient.send(patchRequest, BodyHandlers.ofString());

            if (patchResponse.statusCode() >= 400) {
                String errorBody = patchResponse.body();
                String errorMessage;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorResp = mapper.readValue(errorBody, Map.class);
                    Object errorObj = errorResp.get("error");
                    if (errorObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> err = (Map<String, Object>) errorObj;
                        errorMessage = String.valueOf(err.getOrDefault("message", errorBody));
                    } else if (errorResp.containsKey("message")) {
                        errorMessage = String.valueOf(errorResp.get("message"));
                    } else {
                        errorMessage = errorBody;
                    }
                } catch (Exception ignored) {
                    errorMessage = errorBody;
                }
                logger.warn("Spanner DDL failed (HTTP {}): {}", patchResponse.statusCode(), errorMessage);
                return mapper.writeValueAsString(Map.of("error", true, "message", extractDdlError(errorMessage)));
            }

            // Wait for the operation to complete (Spanner emulator operations are usually fast)
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> opMap = mapper.readValue(patchResponse.body(), Map.class);
                String opName = (String) opMap.get("name");
                if (opName != null) {
                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < 5000) {
                        HttpRequest opRequest = HttpRequest.newBuilder()
                            .uri(URI.create(spannerBase + "/v1/" + opName))
                            .GET()
                            .timeout(Duration.ofSeconds(2))
                            .build();
                        HttpResponse<String> opResponse = httpClient.send(opRequest, BodyHandlers.ofString());
                        if (opResponse.statusCode() == 200) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> opStatus = mapper.readValue(opResponse.body(), Map.class);
                            if (Boolean.TRUE.equals(opStatus.get("done"))) {
                                if (opStatus.containsKey("error")) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> err = (Map<String, Object>) opStatus.get("error");
                                    String errMsg = String.valueOf(err.getOrDefault("message", "DDL operation failed"));
                                    return mapper.writeValueAsString(Map.of("error", true, "message", extractDdlError(errMsg)));
                                }
                                break;
                            }
                        }
                        Thread.sleep(100);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to poll Spanner DDL operation status: {}", e.getMessage());
            }

            return mapper.writeValueAsString(Map.of("status", "executed", "statements", statements.size(),
                "response", mapper.readValue(patchResponse.body(), Object.class)));
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Spanner operation: " + operation));
    }

    @SuppressWarnings("unchecked")
    private String spannerCommitMutation(String projectId, Map<String, Object> json, String mutationType) throws Exception {
        String instance = (String) json.get("instance");
        String database = (String) json.get("database");
        String table = (String) json.get("table");
        List<String> columns = (List<String>) json.get("columns");
        List<List<String>> values = (List<List<String>>) json.get("values");

        String dbPath = "projects/" + projectId + "/instances/" + instance + "/databases/" + database;
        String sessionName = null;

        try {
            // 1. Create session
            String sessionUrl = spannerBase + "/v1/" + dbPath + "/sessions";
            String sessionResp = httpPostAndReturn(sessionUrl, "{}", "application/json");
            Map<String, Object> sessionObj = mapper.readValue(sessionResp, Map.class);
            sessionName = (String) sessionObj.get("name");

            if (sessionName == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Failed to create Spanner session"));
            }

            // 2. Build mutation
            // Convert values to string lists for Spanner format
            List<List<String>> stringValues = new ArrayList<>();
            for (List<?> row : values) {
                List<String> rowValues = new ArrayList<>();
                for (Object val : row) {
                    rowValues.add(val != null ? String.valueOf(val) : null);
                }
                stringValues.add(rowValues);
            }

            Map<String, Object> mutationWrite = new LinkedHashMap<>();
            mutationWrite.put("table", table);
            mutationWrite.put("columns", columns);
            mutationWrite.put("values", stringValues);

            Map<String, Object> mutation = new LinkedHashMap<>();
            mutation.put(mutationType, mutationWrite);

            // 3. Commit
            Map<String, Object> commitBody = new LinkedHashMap<>();
            Map<String, Object> txn = new LinkedHashMap<>();
            txn.put("readWrite", Map.of());
            commitBody.put("singleUseTransaction", txn);
            commitBody.put("mutations", List.of(mutation));

            String commitUrl = spannerBase + "/v1/" + sessionName + ":commit";
            httpPost(commitUrl, mapper.writeValueAsString(commitBody), "application/json");

            logger.debug("Committed Spanner {} mutation on {}.{}", mutationType, database, table);
            return mapper.writeValueAsString(Map.of(
                    "status", "committed",
                    "mutationType", mutationType,
                    "table", table,
                    "rowCount", values.size()));
        } finally {
            // 4. Delete session
            if (sessionName != null) {
                try {
                    httpDelete(spannerBase + "/v1/" + sessionName);
                } catch (Exception ignored) {
                    logger.debug("Failed to delete Spanner session: {}", ignored.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String spannerDeleteRows(String projectId, Map<String, Object> json) throws Exception {
        String instance = (String) json.get("instance");
        String database = (String) json.get("database");
        String table = (String) json.get("table");
        List<String> keyColumns = (List<String>) json.get("keyColumns");
        List<List<String>> keyValues = (List<List<String>>) json.get("keyValues");

        String dbPath = "projects/" + projectId + "/instances/" + instance + "/databases/" + database;
        String sessionName = null;

        try {
            // 1. Create session
            String sessionUrl = spannerBase + "/v1/" + dbPath + "/sessions";
            String sessionResp = httpPostAndReturn(sessionUrl, "{}", "application/json");
            Map<String, Object> sessionObj = mapper.readValue(sessionResp, Map.class);
            sessionName = (String) sessionObj.get("name");

            if (sessionName == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Failed to create Spanner session"));
            }

            // 2. Build delete mutation with keySet
            // Convert keyValues to string lists
            List<List<String>> stringKeys = new ArrayList<>();
            for (List<?> key : keyValues) {
                List<String> keyRow = new ArrayList<>();
                for (Object val : key) {
                    keyRow.add(val != null ? String.valueOf(val) : null);
                }
                stringKeys.add(keyRow);
            }

            Map<String, Object> keySet = new LinkedHashMap<>();
            keySet.put("keys", stringKeys);

            Map<String, Object> deleteMutation = new LinkedHashMap<>();
            deleteMutation.put("table", table);
            deleteMutation.put("keySet", keySet);

            Map<String, Object> mutation = new LinkedHashMap<>();
            mutation.put("delete", deleteMutation);

            // 3. Commit
            Map<String, Object> commitBody = new LinkedHashMap<>();
            Map<String, Object> txn = new LinkedHashMap<>();
            txn.put("readWrite", Map.of());
            commitBody.put("singleUseTransaction", txn);
            commitBody.put("mutations", List.of(mutation));

            String commitUrl = spannerBase + "/v1/" + sessionName + ":commit";
            httpPost(commitUrl, mapper.writeValueAsString(commitBody), "application/json");

            logger.debug("Deleted Spanner rows from {}.{}", database, table);
            return mapper.writeValueAsString(Map.of(
                    "status", "deleted",
                    "table", table,
                    "keyCount", keyValues.size()));
        } finally {
            // 4. Delete session
            if (sessionName != null) {
                try {
                    httpDelete(spannerBase + "/v1/" + sessionName);
                } catch (Exception ignored) {
                    logger.debug("Failed to delete Spanner session: {}", ignored.getMessage());
                }
            }
        }
    }

    // ========== BigQuery ==========

    @SuppressWarnings("unchecked")
    private String mutateBigQuery(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = config.getProjectId();

        if ("datasets".equals(operation) && subOp == null) {
            // Create dataset
            String datasetId = (String) json.get("datasetId");
            if (datasetId == null || datasetId.isBlank()) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Missing datasetId"));
            }
            String description = (String) json.getOrDefault("description", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = (Map<String, Object>) json.getOrDefault("labels", Map.of());

            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("projectId", projectId);
            ref.put("datasetId", datasetId);
            body.put("datasetReference", ref);
            if (!description.isEmpty()) body.put("description", description);
            if (!labels.isEmpty()) body.put("labels", labels);

            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
            httpPostAndReturn(url, mapper.writeValueAsString(body), "application/json");
            logger.info("Created BigQuery dataset: {}", datasetId);
            return mapper.writeValueAsString(Map.of("status", "created", "dataset", datasetId));
        }
        if ("datasets".equals(operation) && "delete".equals(subOp)) {
            // Delete dataset (and optionally its tables)
            String datasetId = (String) json.get("datasetId");
            boolean deleteContents = Boolean.TRUE.equals(json.get("deleteContents"));
            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets/" + datasetId;
            if (deleteContents) url += "?deleteContents=true";
            httpDelete(url);
            logger.info("Deleted BigQuery dataset: {}", datasetId);
            return mapper.writeValueAsString(Map.of("status", "deleted", "dataset", datasetId));
        }
        if ("tables".equals(operation) && subOp == null) {
            // Create table
            String datasetId = (String) json.get("datasetId");
            String tableId = (String) json.get("tableId");
            if (datasetId == null || tableId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Missing datasetId or tableId"));
            }
            String description = (String) json.getOrDefault("description", "");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> schemaFields = (List<Map<String, Object>>) json.get("schema");
            String tableType = (String) json.getOrDefault("tableType", "TABLE");
            @SuppressWarnings("unchecked")
            Map<String, Object> timePartitioning = (Map<String, Object>) json.get("timePartitioning");
            @SuppressWarnings("unchecked")
            List<String> clustering = (List<String>) json.get("clustering");
            String viewQuery = (String) json.get("viewQuery");

            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("projectId", projectId);
            ref.put("datasetId", datasetId);
            ref.put("tableId", tableId);
            body.put("tableReference", ref);

            if ("VIEW".equals(tableType)) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("query", viewQuery != null ? viewQuery : "SELECT 1");
                view.put("useLegacySql", false);
                body.put("view", view);
            } else {
                if (schemaFields != null && !schemaFields.isEmpty()) {
                    Map<String, Object> schema = new LinkedHashMap<>();
                    schema.put("fields", schemaFields);
                    body.put("schema", schema);
                }
                if (timePartitioning != null) body.put("timePartitioning", timePartitioning);
                if (clustering != null && !clustering.isEmpty()) body.put("clustering", Map.of("fields", clustering));
            }
            if (!description.isEmpty()) body.put("description", description);

            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets/" + datasetId + "/tables";
            httpPostAndReturn(url, mapper.writeValueAsString(body), "application/json");
            logger.info("Created BigQuery {}: {}.{}", tableType, datasetId, tableId);
            return mapper.writeValueAsString(Map.of("status", "created", "dataset", datasetId, "table", tableId, "type", tableType));
        }
        if ("tables".equals(operation) && "delete".equals(subOp)) {
            // Delete table
            String datasetId = (String) json.get("datasetId");
            String tableId = (String) json.get("tableId");
            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets/" + datasetId + "/tables/" + tableId;
            httpDelete(url);
            logger.info("Deleted BigQuery table: {}.{}", datasetId, tableId);
            return mapper.writeValueAsString(Map.of("status", "deleted", "dataset", datasetId, "table", tableId));
        }
        if ("rows".equals(operation) && subOp == null) {
            // Insert row via insertAll API
            String dataset = (String) json.get("dataset");
            String table = (String) json.get("table");
            Map<String, Object> row = (Map<String, Object>) json.get("row");

            List<Map<String, Object>> insertRows = new ArrayList<>();
            Map<String, Object> insertRow = new LinkedHashMap<>();
            insertRow.put("json", row);
            insertRows.add(insertRow);

            Map<String, Object> insertBody = new LinkedHashMap<>();
            insertBody.put("rows", insertRows);

            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId
                    + "/datasets/" + dataset + "/tables/" + table + "/insertAll";
            String response = httpPostAndReturn(url, mapper.writeValueAsString(insertBody), "application/json");

            logger.debug("Inserted row into BigQuery {}.{}", dataset, table);
            return mapper.writeValueAsString(Map.of("status", "inserted", "dataset", dataset, "table", table));
        }
        if ("rows".equals(operation) && "delete".equals(subOp)) {
            // Delete rows via DML query
            String dataset = (String) json.get("dataset");
            String table = (String) json.get("table");
            String whereClause = (String) json.get("whereClause");

            // Validate whereClause - only allow simple comparisons to prevent SQL injection
            if (whereClause != null && !whereClause.matches("^[\\w\\s=<>'].+$")) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid whereClause format"));
            }

            String dml = "DELETE FROM `" + dataset + "." + table + "` WHERE " + whereClause;
            Map<String, Object> queryBody = new LinkedHashMap<>();
            queryBody.put("query", dml);
            queryBody.put("useLegacySql", false);

            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
            String response = httpPostAndReturn(url, mapper.writeValueAsString(queryBody), "application/json");

            logger.debug("Deleted rows from BigQuery {}.{} where {}", dataset, table, whereClause);
            return mapper.writeValueAsString(Map.of("status", "deleted", "dataset", dataset, "table", table));
        }
        if ("rows".equals(operation) && "update".equals(subOp)) {
            // Update rows via DML query
            String dataset = (String) json.get("dataset");
            String table = (String) json.get("table");
            @SuppressWarnings("unchecked")
            Map<String, Object> setValues = (Map<String, Object>) json.get("setValues");
            String whereClause = (String) json.get("whereClause");

            if (setValues == null || setValues.isEmpty()) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Missing setValues"));
            }

            // Build SET clause
            StringBuilder setClause = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Object> entry : setValues.entrySet()) {
                if (!first) setClause.append(", ");
                String col = entry.getKey();
                Object val = entry.getValue();
                setClause.append(col).append(" = ");
                if (val == null) {
                    setClause.append("NULL");
                } else if (val instanceof Number) {
                    setClause.append(val);
                } else if (val instanceof Boolean) {
                    setClause.append(val);
                } else {
                    setClause.append("'").append(val.toString().replace("'", "''")).append("'");
                }
                first = false;
            }

            String dml = "UPDATE `" + dataset + "." + table + "` SET " + setClause;
            if (whereClause != null && !whereClause.isBlank()) {
                dml += " WHERE " + whereClause;
            }

            Map<String, Object> queryBody = new LinkedHashMap<>();
            queryBody.put("query", dml);
            queryBody.put("useLegacySql", false);

            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
            httpPostAndReturn(url, mapper.writeValueAsString(queryBody), "application/json");

            logger.debug("Updated rows in BigQuery {}.{} with SET {}", dataset, table, setClause);
            return mapper.writeValueAsString(Map.of("status", "updated", "dataset", dataset, "table", table));
        }
        if ("merge".equals(operation)) {
            // MERGE statement for upsert operations
            String dataset = (String) json.get("dataset");
            String table = (String) json.get("table");
            String sourceQuery = (String) json.get("sourceQuery");
            String mergeCondition = (String) json.get("mergeCondition");
            @SuppressWarnings("unchecked")
            Map<String, Object> updateSet = (Map<String, Object>) json.get("updateSet");
            @SuppressWarnings("unchecked")
            Map<String, Object> insertValues = (Map<String, Object>) json.get("insertValues");

            if (sourceQuery == null || mergeCondition == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Missing sourceQuery or mergeCondition"));
            }

            StringBuilder mergeSql = new StringBuilder();
            mergeSql.append("MERGE `").append(dataset).append(".").append(table).append("` T\n");
            mergeSql.append("USING (").append(sourceQuery).append(") S\n");
            mergeSql.append("ON (").append(mergeCondition).append(")\n");

            if (updateSet != null && !updateSet.isEmpty()) {
                mergeSql.append("WHEN MATCHED THEN UPDATE SET ");
                boolean first = true;
                for (Map.Entry<String, Object> entry : updateSet.entrySet()) {
                    if (!first) mergeSql.append(", ");
                    mergeSql.append("T.").append(entry.getKey()).append(" = S.").append(entry.getValue());
                    first = false;
                }
                mergeSql.append("\n");
            }

            if (insertValues != null && !insertValues.isEmpty()) {
                mergeSql.append("WHEN NOT MATCHED THEN INSERT (");
                mergeSql.append(String.join(", ", insertValues.keySet()));
                mergeSql.append(") VALUES (");
                mergeSql.append(String.join(", ", insertValues.values().stream().map(v -> "S." + v).toList()));
                mergeSql.append(")\n");
            }

            Map<String, Object> queryBody = new LinkedHashMap<>();
            queryBody.put("query", mergeSql.toString());
            queryBody.put("useLegacySql", false);

            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
            httpPostAndReturn(url, mapper.writeValueAsString(queryBody), "application/json");

            logger.debug("Executed MERGE on BigQuery {}.{}", dataset, table);
            return mapper.writeValueAsString(Map.of("status", "merged", "dataset", dataset, "table", table));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid BigQuery operation: " + operation));
    }

    // ========== Secret Manager (PostgreSQL) ==========

    @SuppressWarnings("unchecked")
    private String mutateSecretManager(String operation, String subOp, Map<String, Object> json) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Persistence disabled"));
        }
        String projectId = config.getProjectId();

        if ("secrets".equals(operation) && subOp == null) {
            // Create secret with value
            String name = (String) json.get("name");
            String value = (String) json.get("value");

            // Insert secret
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO secrets (project_id, secret_id, labels) VALUES (?, ?, '{}') " +
                         "ON CONFLICT (project_id, secret_id) DO NOTHING")) {
                ps.setString(1, projectId);
                ps.setString(2, name);
                ps.executeUpdate();
            }

            // Insert version with value
            if (value != null) {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "INSERT INTO secret_versions (project_id, secret_id, version_number, payload, state) " +
                             "VALUES (?, ?, COALESCE((SELECT MAX(version_number) FROM secret_versions " +
                             "WHERE project_id = ? AND secret_id = ?), 0) + 1, ?, 'ENABLED') ")) {
                    ps.setString(1, projectId);
                    ps.setString(2, name);
                    ps.setString(3, projectId);
                    ps.setString(4, name);
                    ps.setBytes(5, value.getBytes(StandardCharsets.UTF_8));
                    ps.executeUpdate();
                }
            }

            logger.debug("Created secret: {}", name);
            return mapper.writeValueAsString(Map.of("status", "created", "name", name));
        }
        if ("secrets".equals(operation) && "delete".equals(subOp)) {
            // Delete secret and all versions
            String name = (String) json.get("name");

            try (Connection conn = dataSource.getConnection()) {
                // Delete versions first (foreign key)
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM secret_versions WHERE project_id = ? AND secret_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, name);
                    ps.executeUpdate();
                }
                // Delete secret
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM secrets WHERE project_id = ? AND secret_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, name);
                    ps.executeUpdate();
                }
            }

            logger.debug("Deleted secret: {}", name);
            return mapper.writeValueAsString(Map.of("status", "deleted", "name", name));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Secret Manager operation: " + operation));
    }

    // ========== Memorystore (Valkey) ==========

    @SuppressWarnings("unchecked")
    private String mutateMemorystore(String operation, String subOp, Map<String, Object> json) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Persistence disabled"));
        }

        if ("keys".equals(operation) && subOp == null) {
            return memorystoreUpsert(json);
        }
        if ("keys".equals(operation) && "update".equals(subOp)) {
            return memorystoreUpsert(json);
        }
        if ("keys".equals(operation) && "delete".equals(subOp)) {
            String key = (String) json.get("key");
            int dbIndex = json.containsKey("db") ? ((Number) json.get("db")).intValue() : 0;

            int redisPort = config.getServiceRegistry().getService("memorystore") != null
                    ? config.getServiceRegistry().getService("memorystore").port() : 6379;
            try (Jedis jedis = new Jedis("localhost", redisPort)) {
                jedis.select(dbIndex);
                jedis.del(key);
            }

            logger.debug("Deleted memorystore key '{}' in db{}", key, dbIndex);
            return mapper.writeValueAsString(Map.of("status", "deleted", "key", key, "database", dbIndex));
        }
        if ("flushdb".equals(operation)) {
            int dbIndex = json.containsKey("db") ? ((Number) json.get("db")).intValue() : 0;
            int redisPort = config.getServiceRegistry().getService("memorystore") != null
                    ? config.getServiceRegistry().getService("memorystore").port() : 6379;
            try (Jedis jedis = new Jedis("localhost", redisPort)) {
                jedis.select(dbIndex);
                jedis.flushDB();
            }
            logger.info("Flushed memorystore db{}", dbIndex);
            return mapper.writeValueAsString(Map.of("status", "flushed", "database", dbIndex));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Memorystore operation: " + operation));
    }

    @SuppressWarnings("unchecked")
    private String memorystoreUpsert(Map<String, Object> json) throws Exception {
        String key = (String) json.get("key");
        Object value = json.get("value");
        String type = (String) json.getOrDefault("type", "string");
        int dbIndex = json.containsKey("db") ? ((Number) json.get("db")).intValue() : 0;

        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 6379;

        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            jedis.select(dbIndex);
            switch (type) {
                case "string":
                    // Value is a plain string
                    jedis.set(key, value != null ? value.toString() : "");
                    break;
                case "hash":
                    // Value is a JSON object — parse into Map<String,String>
                    Map<String, Object> hashObj = (Map<String, Object>) value;
                    Map<String, String> hashMap = new LinkedHashMap<>();
                    if (hashObj != null) {
                        for (Map.Entry<String, Object> entry : hashObj.entrySet()) {
                            hashMap.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
                        }
                    }
                    jedis.del(key);
                    if (!hashMap.isEmpty()) {
                        jedis.hset(key, hashMap);
                    }
                    break;
                case "list":
                    // Value is a JSON array
                    List<Object> listItems = (List<Object>) value;
                    jedis.del(key);
                    if (listItems != null && !listItems.isEmpty()) {
                        String[] listArr = listItems.stream()
                                .map(o -> o != null ? o.toString() : "")
                                .toArray(String[]::new);
                        jedis.rpush(key, listArr);
                    }
                    break;
                case "set":
                    // Value is a JSON array
                    List<Object> setItems = (List<Object>) value;
                    jedis.del(key);
                    if (setItems != null && !setItems.isEmpty()) {
                        String[] setArr = setItems.stream()
                                .map(o -> o != null ? o.toString() : "")
                                .toArray(String[]::new);
                        jedis.sadd(key, setArr);
                    }
                    break;
                default:
                    return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown type: " + type));
            }

            // Apply TTL if provided
            Object ttlObj = json.get("ttl");
            if (ttlObj != null && !ttlObj.toString().isBlank()) {
                long ttl = Long.parseLong(ttlObj.toString().trim());
                if (ttl > 0) {
                    jedis.expire(key, ttl);
                }
            }
        }

        logger.debug("Upserted memorystore key '{}' (type={}) in db{}", key, type, dbIndex);
        return mapper.writeValueAsString(Map.of("status", "created", "key", key, "type", type, "database", dbIndex));
    }

    // ========== Firestore ==========

    @SuppressWarnings("unchecked")
    private String mutateFirestore(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = config.getProjectId();

        if ("documents".equals(operation) && subOp == null) {
            // Create/update document
            String collection = (String) json.get("collection");
            String documentId = (String) json.get("documentId");
            Map<String, Object> fields = (Map<String, Object>) json.get("fields");

            // Convert plain fields to Firestore value format
            Map<String, Object> firestoreFields = new LinkedHashMap<>();
            if (fields != null) {
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    firestoreFields.put(entry.getKey(), toFirestoreValue(entry.getValue()));
                }
            }

            Map<String, Object> documentBody = new LinkedHashMap<>();
            documentBody.put("fields", firestoreFields);

            String url = firestoreBase + "/v1/projects/" + projectId
                    + "/databases/(default)/documents/" + collection + "/" + documentId;
            String response = httpPatchAndReturn(url, mapper.writeValueAsString(documentBody));

            logger.debug("Created/updated Firestore document: {}/{}", collection, documentId);
            return mapper.writeValueAsString(Map.of(
                    "status", "created",
                    "collection", collection,
                    "documentId", documentId));
        }
        if ("documents".equals(operation) && "delete".equals(subOp)) {
            // Delete document
            String collection = (String) json.get("collection");
            String documentId = (String) json.get("documentId");

            String url = firestoreBase + "/v1/projects/" + projectId
                    + "/databases/(default)/documents/" + collection + "/" + documentId;
            httpDelete(url);

            logger.debug("Deleted Firestore document: {}/{}", collection, documentId);
            return mapper.writeValueAsString(Map.of(
                    "status", "deleted",
                    "collection", collection,
                    "documentId", documentId));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Firestore operation: " + operation));
    }

    /**
     * Convert a plain Java value to Firestore value format.
     * Strings become {@code {"stringValue": "..."}}, numbers become
     * {@code {"integerValue": "..."}} or {@code {"doubleValue": ...}},
     * booleans become {@code {"booleanValue": ...}}, and nulls become
     * {@code {"nullValue": null}}.
     */
    private Map<String, Object> toFirestoreValue(Object value) {
        Map<String, Object> fv = new LinkedHashMap<>();
        if (value == null) {
            fv.put("nullValue", null);
        } else if (value instanceof String) {
            fv.put("stringValue", value);
        } else if (value instanceof Boolean) {
            fv.put("booleanValue", value);
        } else if (value instanceof Integer || value instanceof Long) {
            fv.put("integerValue", String.valueOf(value));
        } else if (value instanceof Float || value instanceof Double) {
            fv.put("doubleValue", value);
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            Map<String, Object> mapFields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : mapValue.entrySet()) {
                mapFields.put(entry.getKey(), toFirestoreValue(entry.getValue()));
            }
            fv.put("mapValue", Map.of("fields", mapFields));
        } else if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> listValue = (List<Object>) value;
            List<Map<String, Object>> arrayValues = new ArrayList<>();
            for (Object item : listValue) {
                arrayValues.add(toFirestoreValue(item));
            }
            fv.put("arrayValue", Map.of("values", arrayValues));
        } else {
            fv.put("stringValue", String.valueOf(value));
        }
        return fv;
    }

    // ========== Pub/Sub ==========

    @SuppressWarnings("unchecked")
    private String mutatePubSub(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = config.getProjectId();

        if ("topics".equals(operation) && subOp == null) {
            // Create topic
            String topicName = (String) json.get("name");
            String url = pubsubBase + "/v1/projects/" + projectId + "/topics/" + topicName;
            httpPut(url, "{}");
            return mapper.writeValueAsString(Map.of("status", "created", "topic", topicName));
        }

        if ("topics".equals(operation) && "delete".equals(subOp)) {
            // Delete topic — name may be full path (projects/x/topics/y) or short name
            String topicName = (String) json.get("name");
            if (topicName.contains("/")) topicName = topicName.substring(topicName.lastIndexOf("/") + 1);
            String url = pubsubBase + "/v1/projects/" + projectId + "/topics/" + topicName;
            httpDelete(url);
            return mapper.writeValueAsString(Map.of("status", "deleted", "topic", topicName));
        }

        if ("messages".equals(operation) && subOp == null) {
            // Publish message — topic may be full path (projects/x/topics/y) or short name
            String topicName = (String) json.get("topic");
            if (topicName != null && topicName.contains("/")) topicName = topicName.substring(topicName.lastIndexOf("/") + 1);
            String data = (String) json.get("data");
            Map<String, String> attributes = (Map<String, String>) json.get("attributes");

            String encodedData = java.util.Base64.getEncoder().encodeToString(
                    data.getBytes(StandardCharsets.UTF_8));

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("data", encodedData);
            if (attributes != null && !attributes.isEmpty()) {
                message.put("attributes", attributes);
            }

            Map<String, Object> publishBody = Map.of("messages", List.of(message));
            String url = pubsubBase + "/v1/projects/" + projectId + "/topics/" + topicName + ":publish";
            String response = httpPostAndReturn(url, mapper.writeValueAsString(publishBody), "application/json");
            return response;
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Pub/Sub operation: " + operation));
    }

    // ========== Bigtable ==========

    @SuppressWarnings("unchecked")
    private String mutateBigtable(String operation, String subOp, Map<String, Object> json) throws Exception {
        if ("rows".equals(operation) && subOp == null) {
            // Insert/update row
            String tableRef = (String) json.get("table");
            String rowKey = (String) json.get("rowKey");
            // Split instance/table if combined (e.g., "jay-instance/jay-table")
            String instanceId;
            String tableName;
            if (tableRef != null && tableRef.contains("/")) {
                int slash = tableRef.indexOf('/');
                instanceId = tableRef.substring(0, slash);
                tableName = tableRef.substring(slash + 1);
            } else {
                instanceId = (String) json.getOrDefault("instance", "local-instance");
                tableName = tableRef;
            }

            // Build cells from form data - expect columnFamily, column, value OR cells object
            Map<String, Object> cells = new LinkedHashMap<>();
            if (json.containsKey("cells")) {
                Map<String, Object> rawCells = (Map<String, Object>) json.get("cells");
                cells.putAll(rawCells);
            } else {
                // Simple form: columnFamily:column = value
                String cf = (String) json.get("columnFamily");
                String col = (String) json.get("column");
                String val = (String) json.get("value");
                if (cf != null && col != null) {
                    cells.put(cf + ":" + col, val);
                }
            }

            try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                client.mutateRow(config.getProjectId(), instanceId, tableName, rowKey, cells);
            }
            return mapper.writeValueAsString(Map.of("status", "created", "rowKey", rowKey));
        }

        if ("rows".equals(operation) && "delete".equals(subOp)) {
            String tableRef = (String) json.get("table");
            String rowKey = (String) json.get("rowKey");
            // Split instance/table if combined
            String instanceId;
            String tableName;
            if (tableRef != null && tableRef.contains("/")) {
                int slash = tableRef.indexOf('/');
                instanceId = tableRef.substring(0, slash);
                tableName = tableRef.substring(slash + 1);
            } else {
                instanceId = (String) json.getOrDefault("instance", "local-instance");
                tableName = tableRef;
            }

            try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                client.deleteRow(config.getProjectId(), instanceId, tableName, rowKey);
            }
            return mapper.writeValueAsString(Map.of("status", "deleted", "rowKey", rowKey));
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Bigtable operation: " + operation));
    }

    // ========== Cloud Tasks ==========

    private String mutateCloudTasks(String operation, String subOp, Map<String, Object> body) throws Exception {
        String projectId = config.getProjectId();

        if ("queues".equals(operation) && subOp == null) {
            // Create queue
            String queueId = (String) body.get("name");
            String locationId = (String) body.getOrDefault("location", "us-central1");
            int maxAttempts = body.containsKey("maxAttempts") ? ((Number) body.get("maxAttempts")).intValue() : 5;

            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                     "INSERT INTO task_queues (project_id, queue_id, location_id, state, max_attempts) " +
                     "VALUES (?, ?, ?, 'RUNNING', ?) ON CONFLICT (project_id, queue_id) DO NOTHING")) {
                ps.setString(1, projectId);
                ps.setString(2, queueId);
                ps.setString(3, locationId);
                ps.setInt(4, maxAttempts);
                ps.executeUpdate();
            }
            return mapper.writeValueAsString(Map.of("status", "created", "queue", queueId));
        }

        if ("queues".equals(operation) && "delete".equals(subOp)) {
            String queueId = (String) body.get("name");
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("DELETE FROM task_queues WHERE project_id = ? AND queue_id = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, queueId);
                ps.executeUpdate();
            }
            return mapper.writeValueAsString(Map.of("status", "deleted", "queue", queueId));
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Cloud Tasks operation: " + operation));
    }

    // ========== HTTP helpers ==========

    private void httpPut(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.warn("HTTP PUT {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP PUT %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
    }

    private void httpPost(String url, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", contentType)
                .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
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

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.warn("HTTP POST {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("Spanner API error (%d): %s",
                    response.statusCode(), errorBody));
        }
        return response.body();
    }

    private String httpPatchAndReturn(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.warn("HTTP PATCH {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP PATCH %s failed with status %d: %s",
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

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.debug("HTTP DELETE {} failed ({})", url, response.statusCode());
        }
    }

    // ========== Response helpers ==========

    private com.linecorp.armeria.common.HttpResponse errorResponse(HttpStatus status, String message) {
        try {
            Map<String, Object> error = Map.of(
                    "error", true,
                    "message", message != null ? message : "Unknown error"
            );
            return com.linecorp.armeria.common.HttpResponse.of(status,
                    MediaType.JSON, mapper.writeValueAsString(error));
        } catch (Exception e) {
            return com.linecorp.armeria.common.HttpResponse.of(status,
                    MediaType.PLAIN_TEXT_UTF_8, message != null ? message : "Unknown error");
        }
    }

    // --- Cloud Workflows ---

    private String mutateWorkflows(String operation, String subOp, Map<String, Object> body) throws Exception {
        String projectId = body.containsKey("project_id")
                ? String.valueOf(body.get("project_id"))
                : config.getProjectId();
        String locationId = (String) body.getOrDefault("location", "us-central1");

        // POST /mutate/workflows/execute — create and run an execution
        // Delegates to WorkflowsServiceImpl.createExecution() for full feature parity
        // (connectors, callbacks, env vars, child workflows).
        if ("execute".equals(operation)) {
            String workflowId = (String) body.get("workflow_id");
            if (workflowId == null) return mapper.writeValueAsString(Map.of("error", true, "message", "workflow_id is required"));

            if (workflowsService == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Workflows service not initialized"));
            }

            // Normalize argument to a JSON string — createExecution() expects a JSON string or null.
            // The console may send the argument as a raw object, a string, or omit it entirely.
            String argument = null;
            if (body.containsKey("argument")) {
                Object rawArg = body.get("argument");
                if (rawArg instanceof String) {
                    // Already a string — verify it's valid JSON, otherwise wrap it
                    String argStr = (String) rawArg;
                    try {
                        mapper.readTree(argStr);
                        argument = argStr;
                    } catch (Exception e) {
                        // Not valid JSON — serialize the raw string as a JSON value
                        argument = mapper.writeValueAsString(argStr);
                    }
                } else if (rawArg != null) {
                    argument = mapper.writeValueAsString(rawArg);
                }
            }

            try {
                Map<String, Object> execution = workflowsService.createExecution(projectId, locationId, workflowId, argument);
                // Extract execution_id from the formatted response name
                // name format: projects/{p}/locations/{l}/workflows/{w}/executions/{id}
                String executionName = (String) execution.get("name");
                String executionId = executionName != null
                        ? executionName.substring(executionName.lastIndexOf('/') + 1)
                        : "unknown";
                return mapper.writeValueAsString(Map.of(
                    "status", "started",
                    "execution_id", executionId,
                    "workflow_id", workflowId,
                    "state", execution.getOrDefault("state", "ACTIVE")
                ));
            } catch (IllegalArgumentException e) {
                return mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage()));
            }
        }

        // POST /mutate/workflows/cancel — cancel an execution
        if ("cancel".equals(operation)) {
            String executionId = (String) body.get("execution_id");
            if (executionId == null) return mapper.writeValueAsString(Map.of("error", true, "message", "execution_id is required"));

            if (workflowsService == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Workflows service not initialized"));
            }

            try {
                // Look up execution to find workflowId
                Map<String, Object> execRow = workflowsService.getStore().getExecutionById(executionId);
                if (execRow == null) {
                    return mapper.writeValueAsString(Map.of("error", true, "message", "Execution not found: " + executionId));
                }
                String workflowId = (String) execRow.get("workflow_id");

                workflowsService.cancelExecution(projectId, locationId, workflowId, executionId);
                return mapper.writeValueAsString(Map.of("status", "cancelled", "execution_id", executionId));
            } catch (IllegalStateException e) {
                return mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage()));
            } catch (IllegalArgumentException e) {
                return mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage()));
            }
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown workflows operation: " + operation));
    }
}
