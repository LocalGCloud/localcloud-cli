package com.localcloud.admin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.emulators.workflows.WorkflowsServiceImpl;
import com.localcloud.emulators.cloudsql.CloudSqlEmulator;
import com.localcloud.emulators.bigtable.BigtableEmulator;
import com.localcloud.emulators.memorystore.MemorystoreEmulator;
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
    private CloudSqlEmulator cloudSqlEmulator;
    private BigtableEmulator bigtableEmulator;
    private MemorystoreEmulator memorystoreEmulator;

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

    public void setCloudSqlEmulator(CloudSqlEmulator emulator) {
        this.cloudSqlEmulator = emulator;
    }

    public void setBigtableEmulator(BigtableEmulator emulator) {
        this.bigtableEmulator = emulator;
    }

    public void setMemorystoreEmulator(MemorystoreEmulator emulator) {
        this.memorystoreEmulator = emulator;
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

    private String resolveProject(ServiceRequestContext ctx) {
        String project = ctx.queryParams().get("project");
        return (project != null && !project.isBlank()) ? project : config.getProjectId();
    }

    /**
     * Resolve the effective project for mutation operations.
     * Uses the resolved project from the request body if available, falls back to config default.
     */
    private String effectiveProject(Map<String, Object> json) {
        return json.containsKey("_projectId") ? (String) json.get("_projectId") : config.getProjectId();
    }

    @Post("/{service}/{operation}")
    public com.linecorp.armeria.common.HttpResponse mutate(ServiceRequestContext ctx,
                                                            @Param("service") String service,
                                                            @Param("operation") String operation,
                                                            AggregatedHttpRequest request) {
        try {
            String body = request.contentUtf8();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);

            // Inject resolved project into JSON body for service-specific methods
            String resolvedProject = resolveProject(ctx);
            json.putIfAbsent("_projectId", resolvedProject);

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
                case "cloudscheduler" -> mutateCloudScheduler(operation, null, json);
                case "cloudfunctions" -> mutateCloudFunctions(operation, null, json);
                case "alloydb" -> mutateAlloyDB(operation, null, json);
                case "dataproc" -> mutateDataproc(operation, null, json);
                case "cloudiam" -> mutateCloudIAM(operation, null, json);
                case "kms" -> mutateKms(operation, null, json);
                case "cloudsql" -> mutateCloudSql(operation, null, json);
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
    public com.linecorp.armeria.common.HttpResponse mutateWithSubOp(ServiceRequestContext ctx,
                                                                     @Param("service") String service,
                                                                     @Param("operation") String operation,
                                                                     @Param("subOp") String subOp,
                                                                     AggregatedHttpRequest request) {
        try {
            String body = request.contentUtf8();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(body, Map.class);

            // Inject resolved project into JSON body for service-specific methods
            String resolvedProject = resolveProject(ctx);
            json.putIfAbsent("_projectId", resolvedProject);

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
                case "cloudscheduler" -> mutateCloudScheduler(operation, subOp, json);
                case "cloudfunctions" -> mutateCloudFunctions(operation, subOp, json);
                case "alloydb" -> mutateAlloyDB(operation, subOp, json);
                case "dataproc" -> mutateDataproc(operation, subOp, json);
                case "cloudiam" -> mutateCloudIAM(operation, subOp, json);
                case "kms" -> mutateKms(operation, subOp, json);
                case "cloudsql" -> mutateCloudSql(operation, subOp, json);
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

    // ========== New facade metadata CRUD ==========

    private String mutateCloudScheduler(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = effectiveProject(json);
        if ("jobs".equals(operation) && subOp == null) {
            String name = stringValue(json, "name");
            String schedule = stringValue(json, "schedule");
            if (name == null || schedule == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "name and schedule are required"));
            }
            String locationId = locationFromResource(name, "us-central1");
            String jobId = idFromResource(name, "jobs");
            String parent = "projects/" + projectId + "/locations/" + locationId;
            com.google.cloud.scheduler.v1.Job.Builder job = com.google.cloud.scheduler.v1.Job.newBuilder()
                    .setName(parent + "/jobs/" + jobId)
                    .setSchedule(schedule)
                    .setTimeZone(defaultString(stringValue(json, "timeZone"), "UTC"))
                    .setState(com.google.cloud.scheduler.v1.Job.State.ENABLED);
            String targetUrl = stringValue(json, "targetUrl");
            if (targetUrl != null) {
                job.setHttpTarget(com.google.cloud.scheduler.v1.HttpTarget.newBuilder()
                        .setUri(targetUrl)
                        .setHttpMethod(com.google.cloud.scheduler.v1.HttpMethod.GET));
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO scheduler_jobs
                         (project_id, location_id, job_id, schedule, time_zone, state, job_proto)
                         VALUES (?, ?, ?, ?, ?, ?, ?)
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, jobId);
                ps.setString(4, schedule);
                ps.setString(5, job.getTimeZone());
                ps.setString(6, job.getState().name());
                ps.setBytes(7, job.build().toByteArray());
                ps.executeUpdate();
            }
            return mapper.writeValueAsString(Map.of("status", "created", "name", parent + "/jobs/" + jobId));
        }
        if ("jobs".equals(operation) && "delete".equals(subOp)) {
            String name = stringValue(json, "name");
            if (name == null) return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            String jobId = idFromResource(name, "jobs");
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM scheduler_executions WHERE job_name = ? OR job_name LIKE ?")) {
                    ps.setString(1, name);
                    ps.setString(2, "%/jobs/" + jobId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM scheduler_jobs WHERE project_id = ? AND job_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, jobId);
                    int count = ps.executeUpdate();
                    return mapper.writeValueAsString(Map.of("status", "deleted", "count", count));
                }
            }
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Cloud Scheduler operation: " + operation));
    }

    private String mutateCloudFunctions(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = effectiveProject(json);
        if ("functions".equals(operation) && subOp == null) {
            String name = stringValue(json, "name");
            String runtime = stringValue(json, "runtime");
            String entryPoint = stringValue(json, "entryPoint");
            if (name == null || runtime == null || entryPoint == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "name, runtime, and entryPoint are required"));
            }
            String locationId = locationFromResource(name, "us-central1");
            String functionId = idFromResource(name, "functions");
            String fullName = "projects/" + projectId + "/locations/" + locationId + "/functions/" + functionId;
            com.google.cloud.functions.v2.Function function = com.google.cloud.functions.v2.Function.newBuilder()
                    .setName(fullName)
                    .setBuildConfig(com.google.cloud.functions.v2.BuildConfig.newBuilder()
                            .setRuntime(runtime)
                            .setEntryPoint(entryPoint))
                    .setState(com.google.cloud.functions.v2.Function.State.ACTIVE)
                    .setCreateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(java.time.Instant.now()))
                    .setUpdateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(java.time.Instant.now()))
                    .build();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO cloud_functions
                         (project_id, location_id, function_id, runtime, entry_point, state, function_proto)
                         VALUES (?, ?, ?, ?, ?, ?, ?)
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, functionId);
                ps.setString(4, runtime);
                ps.setString(5, entryPoint);
                ps.setString(6, function.getState().name());
                ps.setBytes(7, function.toByteArray());
                ps.executeUpdate();
            }
            return mapper.writeValueAsString(Map.of("status", "created", "name", fullName));
        }
        if ("functions".equals(operation) && "delete".equals(subOp)) {
            String name = stringValue(json, "name");
            if (name == null) return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            String functionId = idFromResource(name, "functions");
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM cloud_functions WHERE project_id = ? AND function_id = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, functionId);
                return mapper.writeValueAsString(Map.of("status", "deleted", "count", ps.executeUpdate()));
            }
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Cloud Functions operation: " + operation));
    }

    private String mutateAlloyDB(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = effectiveProject(json);
        if ("clusters".equals(operation) && subOp == null) {
            String name = stringValue(json, "name");
            if (name == null) return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            String locationId = locationFromResource(name, "us-central1");
            String clusterId = idFromResource(name, "clusters");
            String fullName = "projects/" + projectId + "/locations/" + locationId + "/clusters/" + clusterId;
            String databaseName = com.localcloud.emulators.common.GrpcSupport.safeDatabaseName(clusterId);
            com.google.cloud.alloydb.v1.Cluster cluster = com.google.cloud.alloydb.v1.Cluster.newBuilder()
                    .setName(fullName)
                    .setState(com.google.cloud.alloydb.v1.Cluster.State.READY)
                    .setCreateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(java.time.Instant.now()))
                    .setUpdateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(java.time.Instant.now()))
                    .build();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO alloydb_clusters
                         (project_id, location_id, cluster_id, database_name, cluster_proto)
                         VALUES (?, ?, ?, ?, ?)
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, databaseName);
                ps.setBytes(5, cluster.toByteArray());
                ps.executeUpdate();
            }
            createAlloyDBDatabaseMetadata(projectId, locationId, clusterId, databaseName, databaseName);
            createAlloyDBPhysicalDatabase(databaseName);
            return mapper.writeValueAsString(Map.of("status", "created", "name", fullName));
        }
        if ("clusters".equals(operation) && "delete".equals(subOp)) {
            String name = stringValue(json, "name");
            if (name == null) return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            String locationId = locationFromResource(name, "us-central1");
            String clusterId = idFromResource(name, "clusters");
            List<String> databaseNames = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement find = conn.prepareStatement(
                         "SELECT physical_name FROM alloydb_databases WHERE project_id = ? AND location_id = ? AND cluster_id = ?")) {
                find.setString(1, projectId);
                find.setString(2, locationId);
                find.setString(3, clusterId);
                try (var rs = find.executeQuery()) {
                    while (rs.next()) databaseNames.add(rs.getString(1));
                }
            }
            if (databaseNames.isEmpty()) {
                Map<String, String> cluster = findAlloyDBCluster(projectId, clusterId);
                if (cluster != null) databaseNames.add(cluster.get("database_name"));
            }
            int count;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM alloydb_clusters WHERE project_id = ? AND location_id = ? AND cluster_id = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                count = ps.executeUpdate();
            }
            for (String databaseName : databaseNames) dropAlloyDBPhysicalDatabase(databaseName);
            return mapper.writeValueAsString(Map.of("status", "deleted", "count", count));
        }
        if ("instances".equals(operation) && subOp == null) {
            String clusterId = stringValue(json, "clusterId");
            String name = stringValue(json, "name");
            if (clusterId == null || name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId and name are required"));
            }
            Map<String, String> cluster = findAlloyDBCluster(projectId, clusterId);
            if (cluster == null) return mapper.writeValueAsString(Map.of("error", true, "message", "cluster not found"));
            String locationId = cluster.get("location_id");
            String instanceId = idFromResource(name, "instances");
            String parent = "projects/" + projectId + "/locations/" + locationId + "/clusters/" + clusterId;
            String fullName = parent + "/instances/" + instanceId;
            var now = java.time.Instant.now();
            com.google.cloud.alloydb.v1.Instance instance = com.google.cloud.alloydb.v1.Instance.newBuilder()
                    .setName(fullName)
                    .setState(com.google.cloud.alloydb.v1.Instance.State.READY)
                    .setCreateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(now))
                    .setUpdateTime(com.localcloud.emulators.common.GrpcSupport.timestamp(now))
                    .build();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO alloydb_instances
                         (project_id, location_id, cluster_id, instance_id, instance_proto)
                         VALUES (?, ?, ?, ?, ?)
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, instanceId);
                ps.setBytes(5, instance.toByteArray());
                ps.executeUpdate();
            }
            return mapper.writeValueAsString(Map.of("status", "created", "name", fullName));
        }
        if ("instances".equals(operation) && "delete".equals(subOp)) {
            String clusterId = stringValue(json, "clusterId");
            String name = stringValue(json, "name");
            if (clusterId == null || name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId and name are required"));
            }
            String instanceId = idFromResource(name, "instances");
            int count;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM alloydb_instances WHERE project_id = ? AND cluster_id = ? AND instance_id = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, clusterId);
                ps.setString(3, instanceId);
                count = ps.executeUpdate();
            }
            return mapper.writeValueAsString(Map.of("status", "deleted", "count", count));
        }
        if ("databases".equals(operation) && subOp == null) {
            String clusterId = stringValue(json, "clusterId");
            String databaseName = stringValue(json, "name");
            if (clusterId == null || databaseName == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId and name are required"));
            }
            Map<String, String> cluster = findAlloyDBCluster(projectId, clusterId);
            if (cluster == null) return mapper.writeValueAsString(Map.of("error", true, "message", "cluster not found"));
            String physicalName = safeAlloyDBPhysicalDatabaseName(clusterId, databaseName);
            createAlloyDBPhysicalDatabase(physicalName);
            createAlloyDBDatabaseMetadata(projectId, cluster.get("location_id"), clusterId, databaseName, physicalName);
            return mapper.writeValueAsString(Map.of("status", "created", "database", databaseName));
        }
        if ("databases".equals(operation) && "delete".equals(subOp)) {
            String clusterId = stringValue(json, "clusterId");
            String databaseName = stringValue(json, "name");
            if (clusterId == null || databaseName == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId and name are required"));
            }
            Map<String, String> database = findAlloyDBDatabase(projectId, clusterId, databaseName);
            if (database == null) return mapper.writeValueAsString(Map.of("error", true, "message", "database not found"));
            int count;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         DELETE FROM alloydb_databases
                         WHERE project_id = ? AND location_id = ? AND cluster_id = ? AND database_name = ?
                         """)) {
                ps.setString(1, projectId);
                ps.setString(2, database.get("location_id"));
                ps.setString(3, clusterId);
                ps.setString(4, databaseName);
                count = ps.executeUpdate();
            }
            dropAlloyDBPhysicalDatabase(database.get("physical_name"));
            return mapper.writeValueAsString(Map.of("status", "deleted", "count", count));
        }
        if ("tables".equals(operation) && subOp == null) {
            String clusterId = stringValue(json, "clusterId");
            String databaseName = stringValue(json, "database");
            String ddl = stringValue(json, "ddl");
            if (clusterId == null || databaseName == null || ddl == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId, database, and ddl are required"));
            }
            String physicalName = findAlloyDBPhysicalDatabase(projectId, clusterId, databaseName);
            if (physicalName == null) return mapper.writeValueAsString(Map.of("error", true, "message", "database not found"));
            List<String> statements = splitSqlStatements(ddl);
            try (Connection conn = dataSource.getConnection(physicalName); var stmt = conn.createStatement()) {
                for (String statement : statements) stmt.execute(statement);
            }
            return mapper.writeValueAsString(Map.of("status", "created", "count", statements.size()));
        }
        if ("tables".equals(operation) && "delete".equals(subOp)) {
            String clusterId = stringValue(json, "clusterId");
            String databaseName = stringValue(json, "database");
            String table = stringValue(json, "table");
            if (clusterId == null || databaseName == null || table == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId, database, and table are required"));
            }
            String physicalName = findAlloyDBPhysicalDatabase(projectId, clusterId, databaseName);
            if (physicalName == null) return mapper.writeValueAsString(Map.of("error", true, "message", "database not found"));
            try (Connection conn = dataSource.getConnection(physicalName); var stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + quoteIdentifier(table));
            }
            return mapper.writeValueAsString(Map.of("status", "deleted", "table", table));
        }
        if ("rows".equals(operation) && subOp == null) {
            String clusterId = stringValue(json, "clusterId");
            String databaseName = stringValue(json, "database");
            String table = stringValue(json, "table");
            Map<String, Object> row = objectMap(json.get("row"));
            if (clusterId == null || databaseName == null || table == null || row.isEmpty()) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId, database, table, and row are required"));
            }
            String physicalName = findAlloyDBPhysicalDatabase(projectId, clusterId, databaseName);
            if (physicalName == null) return mapper.writeValueAsString(Map.of("error", true, "message", "database not found"));
            insertAlloyDBRow(physicalName, table, row);
            return mapper.writeValueAsString(Map.of("status", "inserted", "count", 1));
        }
        if ("rows".equals(operation) && "update".equals(subOp)) {
            String clusterId = stringValue(json, "clusterId");
            String databaseName = stringValue(json, "database");
            String table = stringValue(json, "table");
            String keyColumn = stringValue(json, "keyColumn");
            Object keyValue = json.get("keyValue");
            Map<String, Object> row = objectMap(json.get("row"));
            if (clusterId == null || databaseName == null || table == null || keyColumn == null || row.isEmpty()) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId, database, table, keyColumn, and row are required"));
            }
            String physicalName = findAlloyDBPhysicalDatabase(projectId, clusterId, databaseName);
            if (physicalName == null) return mapper.writeValueAsString(Map.of("error", true, "message", "database not found"));
            int count = updateAlloyDBRow(physicalName, table, keyColumn, keyValue, row);
            return mapper.writeValueAsString(Map.of("status", "updated", "count", count));
        }
        if ("rows".equals(operation) && "delete".equals(subOp)) {
            String clusterId = stringValue(json, "clusterId");
            String databaseName = stringValue(json, "database");
            String table = stringValue(json, "table");
            String keyColumn = stringValue(json, "keyColumn");
            Object keyValue = json.get("keyValue");
            if (clusterId == null || databaseName == null || table == null || keyColumn == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "clusterId, database, table, and keyColumn are required"));
            }
            String physicalName = findAlloyDBPhysicalDatabase(projectId, clusterId, databaseName);
            if (physicalName == null) return mapper.writeValueAsString(Map.of("error", true, "message", "database not found"));
            int count = deleteAlloyDBRow(physicalName, table, keyColumn, keyValue);
            return mapper.writeValueAsString(Map.of("status", "deleted", "count", count));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown AlloyDB operation: " + operation));
    }

    private void createAlloyDBDatabaseMetadata(String projectId, String locationId, String clusterId,
                                               String databaseName, String physicalName) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement find = conn.prepareStatement("""
                    SELECT 1 FROM alloydb_databases
                    WHERE project_id = ? AND location_id = ? AND cluster_id = ? AND database_name = ?
                    """)) {
                find.setString(1, projectId);
                find.setString(2, locationId);
                find.setString(3, clusterId);
                find.setString(4, databaseName);
                try (var rs = find.executeQuery()) {
                    if (rs.next()) return;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO alloydb_databases
                     (project_id, location_id, cluster_id, database_name, physical_name)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, databaseName);
                ps.setString(5, physicalName);
                ps.executeUpdate();
            }
        }
    }

    private Map<String, String> findAlloyDBCluster(String projectId, String clusterId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT location_id, cluster_id, database_name
                     FROM alloydb_clusters
                     WHERE project_id = ? AND cluster_id = ?
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, clusterId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, String> cluster = new LinkedHashMap<>();
                cluster.put("location_id", rs.getString("location_id"));
                cluster.put("cluster_id", rs.getString("cluster_id"));
                cluster.put("database_name", rs.getString("database_name"));
                return cluster;
            }
        }
    }

    private Map<String, String> findAlloyDBDatabase(String projectId, String clusterId, String databaseName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT location_id, database_name, physical_name
                     FROM alloydb_databases
                     WHERE project_id = ? AND cluster_id = ? AND database_name = ?
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, clusterId);
            ps.setString(3, databaseName);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, String> database = new LinkedHashMap<>();
                database.put("location_id", rs.getString("location_id"));
                database.put("database_name", rs.getString("database_name"));
                database.put("physical_name", rs.getString("physical_name"));
                return database;
            }
        }
    }

    private String findAlloyDBPhysicalDatabase(String projectId, String clusterId, String databaseName) throws Exception {
        Map<String, String> database = findAlloyDBDatabase(projectId, clusterId, databaseName);
        if (database != null) return database.get("physical_name");
        Map<String, String> cluster = findAlloyDBCluster(projectId, clusterId);
        if (cluster != null && databaseName.equals(cluster.get("database_name"))) return cluster.get("database_name");
        return null;
    }

    private String safeAlloyDBPhysicalDatabaseName(String clusterId, String databaseName) {
        return com.localcloud.emulators.common.GrpcSupport.safeDatabaseName(clusterId + "_" + databaseName);
    }

    private List<String> splitSqlStatements(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> statements = new ArrayList<>();
        for (String statement : text.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) statements.add(trimmed);
        }
        return statements;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }

    private void insertAlloyDBRow(String databaseName, String table, Map<String, Object> row) throws Exception {
        List<String> columns = new ArrayList<>(row.keySet());
        Map<String, String> columnTypes = alloyDBColumnTypes(databaseName, table);
        String sql = "INSERT INTO " + quoteIdentifier(table) + " (" +
                columns.stream().map(this::quoteIdentifier).reduce((a, b) -> a + ", " + b).orElse("") +
                ") VALUES (" + columns.stream().map(c -> alloyDBPlaceholder(columnTypes.get(c.toLowerCase()))).reduce((a, b) -> a + ", " + b).orElse("") + ")";
        try (Connection conn = dataSource.getConnection(databaseName);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < columns.size(); i++) {
                setAlloyDBValue(ps, i + 1, row.get(columns.get(i)));
            }
            ps.executeUpdate();
        }
    }

    private int updateAlloyDBRow(String databaseName, String table, String keyColumn, Object keyValue,
                                 Map<String, Object> row) throws Exception {
        List<String> columns = new ArrayList<>(row.keySet());
        Map<String, String> columnTypes = alloyDBColumnTypes(databaseName, table);
        String sql = "UPDATE " + quoteIdentifier(table) + " SET " +
                columns.stream().map(c -> quoteIdentifier(c) + " = " + alloyDBPlaceholder(columnTypes.get(c.toLowerCase()))).reduce((a, b) -> a + ", " + b).orElse("") +
                " WHERE " + quoteIdentifier(keyColumn) + " = " + alloyDBPlaceholder(columnTypes.get(keyColumn.toLowerCase()));
        try (Connection conn = dataSource.getConnection(databaseName);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < columns.size(); i++) {
                setAlloyDBValue(ps, i + 1, row.get(columns.get(i)));
            }
            setAlloyDBValue(ps, columns.size() + 1, keyValue);
            return ps.executeUpdate();
        }
    }

    private int deleteAlloyDBRow(String databaseName, String table, String keyColumn, Object keyValue) throws Exception {
        String sql = "DELETE FROM " + quoteIdentifier(table) + " WHERE " + quoteIdentifier(keyColumn) + " = ?";
        try (Connection conn = dataSource.getConnection(databaseName);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setAlloyDBValue(ps, 1, keyValue);
            return ps.executeUpdate();
        }
    }

    private Map<String, String> alloyDBColumnTypes(String databaseName, String table) throws Exception {
        Map<String, String> types = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection(databaseName);
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT column_name, data_type, udt_name
                     FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = ?
                     """)) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String column = rs.getString("column_name");
                    String type = rs.getString("data_type");
                    String udt = rs.getString("udt_name");
                    String normalized = udt != null && !udt.isBlank() ? udt : type;
                    types.put(column.toLowerCase(), normalized == null ? "" : normalized.toLowerCase());
                }
            }
        }
        return types;
    }

    private String alloyDBPlaceholder(String columnType) {
        if ("json".equals(columnType) || "jsonb".equals(columnType)) {
            return "?::" + columnType;
        }
        return "?";
    }

    private void setAlloyDBValue(PreparedStatement ps, int index, Object value) throws Exception {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            ps.setString(index, mapper.writeValueAsString(value));
            return;
        }
        ps.setObject(index, value);
    }

    private void createAlloyDBPhysicalDatabase(String databaseName) {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + quoteIdentifier(databaseName));
        } catch (Exception e) {
            logger.debug("AlloyDB database {} may already exist or cannot be created: {}", databaseName, e.getMessage());
        }
        try (Connection conn = dataSource.getConnection(databaseName); var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (Exception e) {
            logger.debug("AlloyDB database {} pgvector setup skipped: {}", databaseName, e.getMessage());
        }
    }

    private void dropAlloyDBPhysicalDatabase(String databaseName) {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(databaseName));
        } catch (Exception e) {
            logger.debug("AlloyDB database {} drop skipped: {}", databaseName, e.getMessage());
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String mutateDataproc(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = effectiveProject(json);
        if ("clusters".equals(operation) && subOp == null) {
            String clusterName = stringValue(json, "name");
            if (clusterName == null) return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            String region = defaultString(stringValue(json, "region"), "us-central1");
            com.google.cloud.dataproc.v1.Cluster cluster = com.google.cloud.dataproc.v1.Cluster.newBuilder()
                    .setProjectId(projectId)
                    .setClusterName(clusterName)
                    .setStatus(com.google.cloud.dataproc.v1.ClusterStatus.newBuilder()
                            .setState(com.google.cloud.dataproc.v1.ClusterStatus.State.RUNNING)
                            .setStateStartTime(com.localcloud.emulators.common.GrpcSupport.timestamp(java.time.Instant.now())))
                    .build();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO dataproc_clusters (project_id, region, cluster_name, cluster_proto) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, projectId);
                ps.setString(2, region);
                ps.setString(3, clusterName);
                ps.setBytes(4, cluster.toByteArray());
                ps.executeUpdate();
            }
            return mapper.writeValueAsString(Map.of("status", "created", "name", clusterName));
        }
        if ("clusters".equals(operation) && "delete".equals(subOp)) {
            String clusterName = stringValue(json, "name");
            if (clusterName == null) return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM dataproc_clusters WHERE project_id = ? AND cluster_name = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, clusterName);
                return mapper.writeValueAsString(Map.of("status", "deleted", "count", ps.executeUpdate()));
            }
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Dataproc operation: " + operation));
    }

    private String mutateCloudIAM(String operation, String subOp, Map<String, Object> json) throws Exception {
        if ("policies".equals(operation) && subOp == null) {
            String resource = stringValue(json, "resource");
            String role = stringValue(json, "role");
            if (resource == null || role == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "resource and role are required"));
            }
            com.google.iam.v1.Policy policy = com.google.iam.v1.Policy.newBuilder()
                    .addBindings(com.google.iam.v1.Binding.newBuilder()
                            .setRole(role)
                            .addAllMembers(splitMembers(stringValue(json, "members"))))
                    .build();
            String[] parts = splitIamResource(resource);
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement update = conn.prepareStatement("""
                        UPDATE iam_policies
                        SET policy_proto = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE resource_type = ? AND resource_id = ?
                        """)) {
                    update.setBytes(1, policy.toByteArray());
                    update.setString(2, parts[0]);
                    update.setString(3, parts[1]);
                    if (update.executeUpdate() > 0) {
                        return mapper.writeValueAsString(Map.of("status", "updated", "resource", resource));
                    }
                }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO iam_policies (resource_type, resource_id, policy_proto) VALUES (?, ?, ?)")) {
                    insert.setString(1, parts[0]);
                    insert.setString(2, parts[1]);
                    insert.setBytes(3, policy.toByteArray());
                    insert.executeUpdate();
                }
            }
            return mapper.writeValueAsString(Map.of("status", "created", "resource", resource));
        }
        if ("policies".equals(operation) && "delete".equals(subOp)) {
            String resource = stringValue(json, "resource");
            if (resource == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "resource is required"));
            }
            String[] parts = splitIamResource(resource);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM iam_policies WHERE resource_type = ? AND resource_id = ?")) {
                ps.setString(1, parts[0]);
                ps.setString(2, parts[1]);
                return mapper.writeValueAsString(Map.of("status", "deleted", "count", ps.executeUpdate()));
            }
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Cloud IAM operation: " + operation));
    }

    // ========== Cloud KMS (PostgreSQL) ==========

    private String mutateKms(String operation, String subOp, Map<String, Object> json) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Persistence disabled"));
        }
        String projectId = effectiveProject(json);

        if ("keyrings".equals(operation) && subOp == null) {
            String keyRingId = stringValue(json, "keyRingId");
            String locationId = stringValue(json, "locationId", "global");
            if (keyRingId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "keyRingId is required"));
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO kms_key_rings (project_id, location_id, key_ring_id) VALUES (?, ?, ?)")) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, keyRingId);
                ps.executeUpdate();
            }
            logger.debug("Created KMS key ring: {}", keyRingId);
            return mapper.writeValueAsString(Map.of("status", "created", "keyRingId", keyRingId));
        }

        if ("keys".equals(operation) && subOp == null) {
            String keyRingId = stringValue(json, "keyRingId");
            String cryptoKeyId = stringValue(json, "cryptoKeyId");
            String locationId = stringValue(json, "locationId", "global");
            if (keyRingId == null || cryptoKeyId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "keyRingId and cryptoKeyId are required"));
            }
            String purpose = stringValue(json, "purpose", "ENCRYPT_DECRYPT");
            String algorithm = stringValue(json, "algorithm", "GOOGLE_SYMMETRIC_ENCRYPTION");
            String labelsJson = stringValue(json, "labels", "{}");

            byte[] keyMaterial = new byte[32];
            new SecureRandom().nextBytes(keyMaterial);

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement keyPs = conn.prepareStatement(
                        "INSERT INTO kms_crypto_keys (project_id, location_id, key_ring_id, crypto_key_id, purpose, algorithm, primary_version, labels) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 1, ?)");
                     PreparedStatement versionPs = conn.prepareStatement(
                        "INSERT INTO kms_crypto_key_versions (project_id, location_id, key_ring_id, crypto_key_id, version_number, state, algorithm, key_material) " +
                        "VALUES (?, ?, ?, ?, 1, 'ENABLED', ?, ?)")) {
                    keyPs.setString(1, projectId);
                    keyPs.setString(2, locationId);
                    keyPs.setString(3, keyRingId);
                    keyPs.setString(4, cryptoKeyId);
                    keyPs.setString(5, purpose);
                    keyPs.setString(6, algorithm);
                    keyPs.setString(7, labelsJson);
                    keyPs.executeUpdate();

                    versionPs.setString(1, projectId);
                    versionPs.setString(2, locationId);
                    versionPs.setString(3, keyRingId);
                    versionPs.setString(4, cryptoKeyId);
                    versionPs.setString(5, algorithm);
                    versionPs.setBytes(6, keyMaterial);
                    versionPs.executeUpdate();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            logger.debug("Created KMS crypto key: {}/{}", keyRingId, cryptoKeyId);
            return mapper.writeValueAsString(Map.of("status", "created", "keyRingId", keyRingId, "cryptoKeyId", cryptoKeyId));
        }

        if ("keys".equals(operation) && "delete".equals(subOp)) {
            String keyRingId = stringValue(json, "keyRingId");
            String cryptoKeyId = stringValue(json, "cryptoKeyId");
            if (keyRingId == null || cryptoKeyId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "keyRingId and cryptoKeyId are required"));
            }
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM kms_crypto_key_versions WHERE project_id = ? AND key_ring_id = ? AND crypto_key_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, keyRingId);
                    ps.setString(3, cryptoKeyId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM kms_crypto_keys WHERE project_id = ? AND key_ring_id = ? AND crypto_key_id = ?")) {
                    ps.setString(1, projectId);
                    ps.setString(2, keyRingId);
                    ps.setString(3, cryptoKeyId);
                    int count = ps.executeUpdate();
                    return mapper.writeValueAsString(Map.of("status", "deleted", "count", count));
                }
            }
        }

        if ("versions".equals(operation)) {
            String keyRingId = stringValue(json, "keyRingId");
            String cryptoKeyId = stringValue(json, "cryptoKeyId");
            Number versionNum = (Number) json.get("version");
            if (keyRingId == null || cryptoKeyId == null || versionNum == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "keyRingId, cryptoKeyId, and version are required"));
            }
            int version = versionNum.intValue();

            if ("enable".equals(subOp) || "disable".equals(subOp) || "destroy".equals(subOp)) {
                String newState = "enable".equals(subOp) ? "ENABLED" : "disable".equals(subOp) ? "DISABLED" : "DESTROYED";
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                         "UPDATE kms_crypto_key_versions SET state = ? WHERE project_id = ? AND key_ring_id = ? AND crypto_key_id = ? AND version_number = ?")) {
                    ps.setString(1, newState);
                    ps.setString(2, projectId);
                    ps.setString(3, keyRingId);
                    ps.setString(4, cryptoKeyId);
                    ps.setInt(5, version);
                    int count = ps.executeUpdate();
                    return mapper.writeValueAsString(Map.of("status", "updated", "state", newState, "count", count));
                }
            }

            if ("setPrimary".equals(subOp)) {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                         "UPDATE kms_crypto_keys SET primary_version = ? " +
                         "WHERE project_id = ? AND key_ring_id = ? AND crypto_key_id = ? " +
                         "AND EXISTS (SELECT 1 FROM kms_crypto_key_versions " +
                         "WHERE project_id = ? AND key_ring_id = ? AND crypto_key_id = ? AND version_number = ?)")) {
                    ps.setInt(1, version);
                    ps.setString(2, projectId);
                    ps.setString(3, keyRingId);
                    ps.setString(4, cryptoKeyId);
                    ps.setString(5, projectId);
                    ps.setString(6, keyRingId);
                    ps.setString(7, cryptoKeyId);
                    ps.setInt(8, version);
                    int count = ps.executeUpdate();
                    if (count == 0) {
                        return mapper.writeValueAsString(Map.of("error", true, "message", "Version not found"));
                    }
                    return mapper.writeValueAsString(Map.of("status", "updated", "primaryVersion", version));
                }
            }
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown KMS operation: " + operation + "/" + subOp));
    }

    private String mutateCloudSql(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = effectiveProject(json);
        if ("instances".equals(operation) && subOp == null) {
            String name = stringValue(json, "name");
            String region = stringValue(json, "region", "us-central1");
            String databaseVersion = stringValue(json, "databaseVersion", "POSTGRES_15");
            String tier = stringValue(json, "tier", "db-custom-1-3840");
            if (name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudsql_instances (project_id, instance_id, region, database_version, tier, state, backend_type, connection_name, settings_json) " +
                     "VALUES (?, ?, ?, ?, ?, 'RUNNABLE', ?, ?, ?)")) {
                String backendType = databaseVersion.startsWith("MYSQL") ? "OPENHALO_MYSQL_COMPAT" : "POSTGRES";
                String connectionName = projectId + ":" + region + ":" + name;
                ps.setString(1, projectId);
                ps.setString(2, name);
                ps.setString(3, region);
                ps.setString(4, databaseVersion);
                ps.setString(5, tier);
                ps.setString(6, backendType);
                ps.setString(7, connectionName);
                ps.setString(8, "{}");
                ps.executeUpdate();
            }
            if (cloudSqlEmulator != null) cloudSqlEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "created", "instance", name, "databaseVersion", databaseVersion));
        }
        if ("instances".equals(operation) && "delete".equals(subOp)) {
            String name = stringValue(json, "name");
            if (name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "name is required"));
            }
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM cloudsql_users WHERE project_id = ? AND instance_id = ?")) {
                        ps.setString(1, projectId);
                        ps.setString(2, name);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM cloudsql_databases WHERE project_id = ? AND instance_id = ?")) {
                        ps.setString(1, projectId);
                        ps.setString(2, name);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM cloudsql_instances WHERE project_id = ? AND instance_id = ?")) {
                        ps.setString(1, projectId);
                        ps.setString(2, name);
                        ps.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            if (cloudSqlEmulator != null) cloudSqlEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "deleted", "instance", name));
        }
        if ("databases".equals(operation) && subOp == null) {
            String instanceId = stringValue(json, "instanceId");
            String name = stringValue(json, "name");
            String charset = stringValue(json, "charset", "UTF8");
            String collation = stringValue(json, "collation", "");
            if (instanceId == null || name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId and name are required"));
            }
            String physical = ("lc_" + projectId + "_" + instanceId + "_" + name).toLowerCase().replaceAll("[^a-z0-9_]", "_");
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudsql_databases (project_id, instance_id, database_name, charset, \"collation\", physical_name) " +
                     "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, projectId);
                ps.setString(2, instanceId);
                ps.setString(3, name);
                ps.setString(4, charset);
                ps.setString(5, collation);
                ps.setString(6, physical);
                ps.executeUpdate();
            }
            if (cloudSqlEmulator != null) cloudSqlEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "created", "database", name, "instance", instanceId));
        }
        if ("databases".equals(operation) && "delete".equals(subOp)) {
            String instanceId = stringValue(json, "instanceId");
            String name = stringValue(json, "name");
            if (instanceId == null || name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId and name are required"));
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM cloudsql_databases WHERE project_id = ? AND instance_id = ? AND database_name = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, instanceId);
                ps.setString(3, name);
                ps.executeUpdate();
            }
            if (cloudSqlEmulator != null) cloudSqlEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "deleted", "database", name, "instance", instanceId));
        }
        if ("users".equals(operation) && subOp == null) {
            String instanceId = stringValue(json, "instanceId");
            String name = stringValue(json, "name");
            String host = stringValue(json, "host", "%");
            String password = stringValue(json, "password", null);
            if (instanceId == null || name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId and name are required"));
            }
            String passwordHash = password == null ? null : java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudsql_users (project_id, instance_id, user_name, host, password_hash) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, projectId);
                ps.setString(2, instanceId);
                ps.setString(3, name);
                ps.setString(4, host);
                ps.setString(5, passwordHash);
                ps.executeUpdate();
            }
            if (cloudSqlEmulator != null) cloudSqlEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "created", "user", name, "instance", instanceId));
        }
        if ("users".equals(operation) && "delete".equals(subOp)) {
            String instanceId = stringValue(json, "instanceId");
            String name = stringValue(json, "name");
            String host = stringValue(json, "host", "%");
            if (instanceId == null || name == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId and name are required"));
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM cloudsql_users WHERE project_id = ? AND instance_id = ? AND user_name = ? AND host = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, instanceId);
                ps.setString(3, name);
                ps.setString(4, host);
                ps.executeUpdate();
            }
            if (cloudSqlEmulator != null) cloudSqlEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "deleted", "user", name, "instance", instanceId));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Cloud SQL operation: " + operation));
    }

    private String mutateBigtableAdmin(String operation, String subOp, Map<String, Object> json) throws Exception {
        // Use BigtableGrpcClient to talk directly to the emulator — no PostgreSQL.
        String projectId = effectiveProject(json);
        if ("instances".equals(operation) && subOp == null) {
            String instanceId = stringValue(json, "instanceId");
            String displayName = stringValue(json, "displayName", instanceId);
            String instanceType = stringValue(json, "instanceType", "PRODUCTION");
            if (instanceId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId is required"));
            }
            try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                client.ensureInstance(projectId, instanceId, displayName, instanceType);
            }
            if (bigtableEmulator != null) bigtableEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "created", "instance", instanceId));
        }
        if ("instances".equals(operation) && "delete".equals(subOp)) {
            String instanceId = stringValue(json, "instanceId");
            if (instanceId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId is required"));
            }
            try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                client.deleteInstance(projectId, instanceId);
            } catch (io.grpc.StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    return mapper.writeValueAsString(Map.of("error", true, "message", "Instance not found: " + instanceId));
                }
                throw e;
            }
            if (bigtableEmulator != null) bigtableEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "deleted", "instance", instanceId));
        }
        if ("tables".equals(operation) && subOp == null) {
            String instanceId = stringValue(json, "instanceId");
            String tableId = stringValue(json, "tableId");
            String granularity = stringValue(json, "granularity", "MILLIS");
            if (instanceId == null || tableId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId and tableId are required"));
            }
            try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                if (client.getInstance(projectId, instanceId) == null) {
                    return mapper.writeValueAsString(Map.of("error", true, "message", "Instance not found: " + instanceId));
                }
                client.ensureTable(projectId, instanceId, tableId, List.of("cf1"), granularity);
            }
            if (bigtableEmulator != null) bigtableEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "created", "table", tableId, "instance", instanceId));
        }
        if ("tables".equals(operation) && "delete".equals(subOp)) {
            String instanceId = stringValue(json, "instanceId");
            String tableId = stringValue(json, "tableId");
            if (instanceId == null || tableId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId and tableId are required"));
            }
            try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
                client.deleteTable(projectId, instanceId, tableId);
            } catch (io.grpc.StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    return mapper.writeValueAsString(Map.of("error", true, "message", "Table not found: " + tableId));
                }
                throw e;
            }
            if (bigtableEmulator != null) bigtableEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "deleted", "table", tableId, "instance", instanceId));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Bigtable operation: " + operation));
    }

    private String mutateMemorystoreAdmin(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = effectiveProject(json);
        if ("instances".equals(operation) && subOp == null) {
            String instanceId = stringValue(json, "instanceId");
            String displayName = stringValue(json, "displayName", instanceId);
            String tier = stringValue(json, "tier", "BASIC");
            String redisVersion = stringValue(json, "redisVersion", "7_0");
            int memorySizeGb = json.containsKey("memorySizeGb") ? ((Number) json.get("memorySizeGb")).intValue() : 1;
            if (instanceId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId is required"));
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO memorystore_instances (project_id, instance_id, display_name, tier, engine, redis_version, port, memory_size_gb, state, host) " +
                     "VALUES (?, ?, ?, ?, 'REDIS', ?, 6379, ?, 'READY', 'localhost')")) {
                ps.setString(1, projectId);
                ps.setString(2, instanceId);
                ps.setString(3, displayName);
                ps.setString(4, tier);
                ps.setString(5, redisVersion);
                ps.setInt(6, memorySizeGb);
                ps.executeUpdate();
            }
            if (memorystoreEmulator != null) memorystoreEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "created", "instance", instanceId));
        }
        if ("instances".equals(operation) && "delete".equals(subOp)) {
            String instanceId = stringValue(json, "instanceId");
            if (instanceId == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "instanceId is required"));
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM memorystore_instances WHERE project_id = ? AND instance_id = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, instanceId);
                ps.executeUpdate();
            }
            if (memorystoreEmulator != null) memorystoreEmulator.incrementRequestCount();
            return mapper.writeValueAsString(Map.of("status", "deleted", "instance", instanceId));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Memorystore operation: " + operation));
    }

    private static String stringValue(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (value == null) return null;
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    private static String stringValue(Map<String, Object> json, String key, String defaultValue) {
        String value = stringValue(json, key);
        return value != null ? value : defaultValue;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String idFromResource(String value, String collection) {
        String marker = "/" + collection + "/";
        int markerIndex = value.indexOf(marker);
        if (markerIndex >= 0) return value.substring(markerIndex + marker.length());
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String locationFromResource(String value, String fallback) {
        String[] parts = value.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("locations".equals(parts[i])) return parts[i + 1];
        }
        return fallback;
    }

    private static List<String> splitMembers(String members) {
        if (members == null) return List.of();
        List<String> values = new ArrayList<>();
        for (String member : members.split(",")) {
            String trimmed = member.trim();
            if (!trimmed.isEmpty()) values.add(trimmed);
        }
        return values;
    }

    private static String[] splitIamResource(String resource) {
        int index = resource.indexOf('/');
        if (index <= 1) return new String[] {"resource", resource};
        return new String[] {resource.substring(0, index), resource.substring(index + 1)};
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

            // Use resolved project from request (inserted by endpoint), fall back to config default
            String projectId = json.containsKey("_projectId") ? (String) json.get("_projectId") : config.getProjectId();
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
        if ("folders".equals(operation) && subOp == null) {
            // Create folder (upload a zero-byte placeholder object ending with /)
            String bucket = (String) json.get("bucket");
            String prefix = (String) json.getOrDefault("prefix", "");
            String name = (String) json.get("name");
            String folderKey = (prefix != null && !prefix.isEmpty() ? prefix : "") + name + "/";

            String url = gcsBase + "/upload/storage/v1/b/" + bucket
                    + "/o?name=" + URLEncoder.encode(folderKey, StandardCharsets.UTF_8)
                    + "&uploadType=media";
            String response = httpPostAndReturn(url, "", "application/x-directory");
            logger.debug("Created GCS folder: {}/{}", bucket, folderKey);
            return mapper.writeValueAsString(Map.of("status", "created", "bucket", bucket, "key", folderKey));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid GCS operation: " + operation));
    }

    // ========== Spanner ==========

    @SuppressWarnings("unchecked")
    private String mutateSpanner(String operation, String subOp, Map<String, Object> json) throws Exception {
        String projectId = effectiveProject(json);

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

    /**
     * Convert a value for Spanner commit mutations.
     * - Booleans pass through as-is.
     * - FLOAT64 passes through as a number (only numeric type that does).
     * - INT64, NUMERIC, and other numerics become strings (per Spanner REST API).
     * - Maps are serialized to JSON strings (for JSON/JSONB columns).
     * - Lists are recursively converted (for ARRAY columns).
     * - Everything else becomes a string.
     */
    private Object spannerValue(Object val, String columnType) {
        if (val == null) return null;
        if (val instanceof Boolean) {
            return val;
        }
        if (val instanceof Number) {
            // FLOAT64 / DOUBLE are the only numeric types the Spanner REST API
            // accepts as JSON numbers.  Everything else (INT64, NUMERIC, etc.)
            // must be a string.
            if (columnType != null && columnType.toUpperCase().contains("FLOAT")) {
                return val;
            }
            return String.valueOf(val);
        }
        if (val instanceof List<?> list) {
            return list.stream().map(v -> spannerValue(v, columnType)).toList();
        }
        if (val instanceof Map) {
            try {
                return mapper.writeValueAsString(val);
            } catch (Exception e) {
                return String.valueOf(val);
            }
        }
        return String.valueOf(val);
    }

    @SuppressWarnings("unchecked")
    private String spannerCommitMutation(String projectId, Map<String, Object> json, String mutationType) throws Exception {
        String instance = (String) json.get("instance");
        String database = (String) json.get("database");
        String table = (String) json.get("table");
        List<String> columns = (List<String>) json.get("columns");
        List<List<?>> values = (List<List<?>>) json.get("values");

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
            // Convert values to Spanner format, preserving arrays for ARRAY columns
            @SuppressWarnings("unchecked")
            Map<String, String> columnTypes = (Map<String, String>) json.get("columnTypes");

            List<List<Object>> mutationValues = new ArrayList<>();
            for (List<?> row : values) {
                List<Object> rowValues = new ArrayList<>();
                for (int i = 0; i < row.size(); i++) {
                    Object val = row.get(i);
                    String colType = (columnTypes != null && i < columns.size()) ? columnTypes.get(columns.get(i)) : null;
                    rowValues.add(spannerValue(val, colType));
                }
                mutationValues.add(rowValues);
            }

            Map<String, Object> mutationWrite = new LinkedHashMap<>();
            mutationWrite.put("table", table);
            mutationWrite.put("columns", columns);
            mutationWrite.put("values", mutationValues);

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
        String projectId = effectiveProject(json);

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
        if ("queries".equals(operation) && subOp == null) {
            // Execute arbitrary SQL (DDL, DML, etc.) against the BigQuery emulator
            String query = (String) json.get("query");
            if (query == null || query.isBlank()) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Missing query"));
            }
            Map<String, Object> queryBody = new LinkedHashMap<>();
            queryBody.put("query", query);
            queryBody.put("useLegacySql", false);

            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
            String response = httpPostAndReturn(url, mapper.writeValueAsString(queryBody), "application/json");
            logger.debug("Executed BigQuery SQL via console: {}", query.length() > 80 ? query.substring(0, 80) + "..." : query);
            return mapper.writeValueAsString(Map.of("status", "executed", "response", response));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid BigQuery operation: " + operation));
    }

    // ========== Secret Manager (PostgreSQL) ==========

    @SuppressWarnings("unchecked")
    private String mutateSecretManager(String operation, String subOp, Map<String, Object> json) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Persistence disabled"));
        }
        String projectId = effectiveProject(json);

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

        // Version management
        if ("versions".equals(operation) && "add".equals(subOp)) {
            String name = (String) json.get("name");
            String value = (String) json.get("value");
            if (value == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Secret value is required"));
            }
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
            logger.debug("Added version to secret: {}", name);
            return mapper.writeValueAsString(Map.of("status", "created", "name", name));
        }
        if ("versions".equals(operation) && ("enable".equals(subOp) || "disable".equals(subOp) || "destroy".equals(subOp))) {
            String name = (String) json.get("name");
            int version = ((Number) json.get("version")).intValue();
            String newState = "enable".equals(subOp) ? "ENABLED" : "disable".equals(subOp) ? "DISABLED" : "DESTROYED";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE secret_versions SET state = ? " +
                     "WHERE project_id = ? AND secret_id = ? AND version_number = ?")) {
                ps.setString(1, newState);
                ps.setString(2, projectId);
                ps.setString(3, name);
                ps.setInt(4, version);
                ps.executeUpdate();
            }
            logger.debug("Updated version {} of secret {} to {}", version, name, newState);
            return mapper.writeValueAsString(Map.of("status", "updated", "name", name, "version", version, "state", newState));
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
        String projectId = effectiveProject(json);

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
        String projectId = effectiveProject(json);

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

        if ("messages".equals(operation) && "mock".equals(subOp)) {
            // Publish mock messages in batch
            String topicName = (String) json.get("topic");
            if (topicName != null && topicName.contains("/")) topicName = topicName.substring(topicName.lastIndexOf("/") + 1);
            int count = json.containsKey("count") ? ((Number) json.get("count")).intValue() : 1;
            count = Math.min(Math.max(count, 1), 100); // clamp 1-100

            @SuppressWarnings("unchecked")
            Map<String, Object> template = (Map<String, Object>) json.get("template");

            List<Map<String, Object>> messages = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Map<String, Object> payload = generateMockPubSubPayload(topicName, template);
                String dataStr = mapper.writeValueAsString(payload);
                String encodedData = java.util.Base64.getEncoder().encodeToString(
                        dataStr.getBytes(StandardCharsets.UTF_8));

                Map<String, Object> message = new LinkedHashMap<>();
                message.put("data", encodedData);

                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("event-type", (String) payload.get("event"));
                attrs.put("source", (String) payload.get("source"));
                attrs.put("region", (String) payload.get("region"));
                attrs.put("content-type", "application/json");
                attrs.put("generated-by", "localcloud-mock-generator");
                message.put("attributes", attrs);

                messages.add(message);
            }

            Map<String, Object> publishBody = Map.of("messages", messages);
            String url = pubsubBase + "/v1/projects/" + projectId + "/topics/" + topicName + ":publish";
            String response = httpPostAndReturn(url, mapper.writeValueAsString(publishBody), "application/json");
            return mapper.writeValueAsString(Map.of("status", "published", "count", count, "response", mapper.readValue(response, Object.class)));
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
                client.mutateRow(effectiveProject(json), instanceId, tableName, rowKey, cells);
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
                client.deleteRow(effectiveProject(json), instanceId, tableName, rowKey);
            }
            return mapper.writeValueAsString(Map.of("status", "deleted", "rowKey", rowKey));
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Unknown Bigtable operation: " + operation));
    }

    // ========== Cloud Tasks ==========

    private String mutateCloudTasks(String operation, String subOp, Map<String, Object> body) throws Exception {
        String projectId = effectiveProject(body);

        if ("queues".equals(operation) && subOp == null) {
            // Create queue with full config support
            String queueId = (String) body.get("name");
            String locationId = (String) body.getOrDefault("location", "us-central1");
            String state = (String) body.getOrDefault("state", "RUNNING");
            int maxAttempts = body.containsKey("maxAttempts") ? ((Number) body.get("maxAttempts")).intValue() : 100;
            double rate = body.containsKey("maxDispatchesPerSecond") ? ((Number) body.get("maxDispatchesPerSecond")).doubleValue() : 500;
            int concurrent = body.containsKey("maxConcurrentDispatches") ? ((Number) body.get("maxConcurrentDispatches")).intValue() : 1000;
            int burst = body.containsKey("maxBurstSize") ? ((Number) body.get("maxBurstSize")).intValue() : 0;
            String minBackoff = (String) body.getOrDefault("minBackoff", "0.100s");
            String maxBackoff = (String) body.getOrDefault("maxBackoff", "3600s");
            int maxDoublings = body.containsKey("maxDoublings") ? ((Number) body.get("maxDoublings")).intValue() : 16;
            String maxRetryDuration = (String) body.getOrDefault("maxRetryDuration", "0s");
            String httpTargetUri = (String) body.get("httpTargetUri");
            String httpTargetMethod = (String) body.get("httpTargetMethod");

            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                     "INSERT INTO task_queues (project_id, location_id, queue_id, state, max_attempts, " +
                     "max_dispatches_per_second, max_concurrent_dispatches, max_burst_size, " +
                     "min_backoff, max_backoff, max_doublings, max_retry_duration, " +
                     "http_target_uri, http_target_method) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (project_id, location_id, queue_id) DO NOTHING")) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, queueId);
                ps.setString(4, state);
                ps.setInt(5, maxAttempts);
                ps.setDouble(6, rate);
                ps.setInt(7, concurrent);
                ps.setInt(8, burst);
                ps.setString(9, minBackoff);
                ps.setString(10, maxBackoff);
                ps.setInt(11, maxDoublings);
                ps.setString(12, maxRetryDuration);
                ps.setString(13, httpTargetUri);
                ps.setString(14, httpTargetMethod);
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

    // ========== Pub/Sub mock payload generation ==========

    private static final String[] EVENT_TYPES = {
        "user.login", "user.logout", "user.signup", "user.profile.updated", "user.deleted",
        "order.created", "order.updated", "order.cancelled", "order.shipped", "order.delivered", "order.refunded",
        "payment.succeeded", "payment.failed", "payment.refunded", "payment.authorized",
        "product.created", "product.updated", "product.deleted", "product.stock.low",
        "invoice.generated", "invoice.sent", "invoice.paid", "invoice.overdue",
        "notification.email.sent", "notification.sms.sent", "notification.push.sent",
        "cart.abandoned", "cart.updated", "cart.checked_out",
        "review.created", "review.updated", "review.flagged",
        "session.started", "session.ended", "page.viewed", "button.clicked",
        "api.request", "api.error", "api.rate_limited",
        "system.health_check", "system.alert", "system.config.changed"
    };

    private static final String[] SOURCES = {
        "auth-service", "order-service", "payment-service", "notification-service",
        "api-gateway", "web-app", "mobile-app", "admin-panel", "cron-job", "webhook"
    };

    private static final String[] REGIONS = {
        "us-central1", "us-east1", "us-west1", "europe-west1", "europe-west4",
        "asia-east1", "asia-northeast1", "australia-southeast1"
    };

    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)",
        "localcloud-sdk/1.0"
    };

    private static final String[] DOMAINS = {"gmail.com", "yahoo.com", "outlook.com", "example.com", "localcloud.dev"};
    private static final String[] FIRST_NAMES = {"John", "Jane", "Alex", "Emily", "Michael", "Sarah", "David", "Jessica", "Robert", "Lisa"};
    private static final String[] LAST_NAMES = {"Smith", "Doe", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis", "Wilson", "Anderson"};
    private static final String[] COUNTRIES = {"US", "GB", "CA", "DE", "FR", "IN", "JP", "AU"};
    private static final String[] CURRENCIES = {"USD", "EUR", "GBP", "JPY", "INR"};
    private static final String[] PAYMENT_METHODS = {"credit_card", "debit_card", "paypal", "bank_transfer", "crypto"};
    private static final String[] CHANNELS = {"email", "sms", "push", "in_app"};
    private static final String[] TEMPLATES = {"welcome", "reset_password", "order_confirm", "shipping_update", "promo"};
    private static final String[] PAGES = {"home", "products", "checkout", "account", "settings", "help"};
    private static final String[] API_RESOURCES = {"users", "orders", "products", "payments", "auth"};
    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};
    private static final int[] STATUS_CODES = {200, 201, 400, 401, 403, 404, 500, 502, 503};

    private static final java.util.Random MOCK_RANDOM = new java.util.Random();

    private static String pickRandom(String[] arr) {
        return arr[MOCK_RANDOM.nextInt(arr.length)];
    }

    private static int pickRandomInt(int[] arr) {
        return arr[MOCK_RANDOM.nextInt(arr.length)];
    }

    private static String uuid() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * Generates a mock Pub/Sub message payload.
     */
    private Map<String, Object> generateMockPubSubPayload(String topicName, Map<String, Object> template) {
        String topic = (topicName != null ? topicName : "events").toLowerCase().replace('-', ' ').replace('_', ' ');
        String event = pickRandom(EVENT_TYPES);
        String source = pickRandom(SOURCES);
        String region = pickRandom(REGIONS);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("id", uuid());
        payload.put("timestamp", java.time.Instant.now().minusSeconds(MOCK_RANDOM.nextInt(604800)).toString());
        payload.put("source", source);
        payload.put("region", region);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", uuid().substring(0, 16));
        metadata.put("userAgent", pickRandom(USER_AGENTS));
        metadata.put("version", (MOCK_RANDOM.nextInt(3) + 1) + "." + MOCK_RANDOM.nextInt(10) + "." + MOCK_RANDOM.nextInt(20));
        payload.put("metadata", metadata);

        if (topic.contains("user") || event.startsWith("user.")) {
            payload.put("userId", uuid());
            payload.put("email", (pickRandom(FIRST_NAMES).toLowerCase() + "." + pickRandom(LAST_NAMES).toLowerCase() + "@" + pickRandom(DOMAINS)));
            payload.put("country", pickRandom(COUNTRIES));
        }

        if (topic.contains("order") || event.startsWith("order.")) {
            payload.put("orderId", uuid());
            payload.put("amount", Math.round((MOCK_RANDOM.nextDouble() * 500 + 10) * 100.0) / 100.0);
            payload.put("currency", pickRandom(CURRENCIES));
            payload.put("itemCount", MOCK_RANDOM.nextInt(10) + 1);
        }

        if (topic.contains("payment") || event.startsWith("payment.")) {
            payload.put("paymentId", uuid());
            payload.put("amount", Math.round((MOCK_RANDOM.nextDouble() * 1000 + 5) * 100.0) / 100.0);
            payload.put("currency", pickRandom(CURRENCIES));
            payload.put("method", pickRandom(PAYMENT_METHODS));
        }

        if (topic.contains("notification") || event.startsWith("notification.")) {
            payload.put("channel", pickRandom(CHANNELS));
            payload.put("recipient", (pickRandom(FIRST_NAMES).toLowerCase() + "." + pickRandom(LAST_NAMES).toLowerCase() + "@" + pickRandom(DOMAINS)));
            payload.put("template", pickRandom(TEMPLATES));
        }

        if (topic.contains("analytics") || topic.contains("tracking") || event.startsWith("session.") || event.startsWith("page.") || event.startsWith("button.")) {
            payload.put("pageUrl", "https://example.com/" + pickRandom(PAGES));
            payload.put("sessionId", uuid().substring(0, 16));
            payload.put("durationMs", MOCK_RANDOM.nextInt(30000) + 100);
        }

        if (topic.contains("api") || event.startsWith("api.")) {
            payload.put("endpoint", "/api/v" + (MOCK_RANDOM.nextInt(3) + 1) + "/" + pickRandom(API_RESOURCES));
            payload.put("method", pickRandom(HTTP_METHODS));
            payload.put("statusCode", pickRandomInt(STATUS_CODES));
            payload.put("latencyMs", MOCK_RANDOM.nextInt(2000) + 10);
        }

        return payload;
    }
}
