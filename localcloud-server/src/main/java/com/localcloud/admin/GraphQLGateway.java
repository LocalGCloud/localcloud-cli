package com.localcloud.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.server.graphql.GraphqlService;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.persistence.PostgresDataSource;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphQLGateway {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLGateway.class);

    private static final String SCHEMA_SDL = """
        type Query {
            spanner: SpannerQueries!
            bigquery: BigQueryQueries!
            logging: LoggingQueries!
            monitoring: MonitoringQueries!
            queryHistory(limit: Int = 20, offset: Int = 0): QueryHistoryResult!
        }

        type SpannerQueries {
            instances: [SpannerInstance!]!
            databases(instance: String!): [SpannerDatabase!]!
            tables(instance: String!, database: String!): [SpannerTable!]!
        }

        type SpannerInstance {
            name: String!
            displayName: String
            state: String
            databaseCount: Int
        }

        type SpannerDatabase {
            name: String!
            state: String
            tableCount: Int
        }

        type SpannerTable {
            name: String!
            database: String!
            instance: String!
            columns: [ColumnInfo!]!
            indexes: [String!]!
        }

        type ColumnInfo {
            name: String!
            type: String!
        }

        type BigQueryQueries {
            datasets: [BigQueryDataset!]!
            tables(datasetId: String!): [BigQueryTable!]!
        }

        type BigQueryDataset {
            id: String!
            displayName: String
        }

        type BigQueryTable {
            name: String!
            columns: [ColumnInfo!]!
        }

        type LoggingQueries {
            entries(limit: Int = 50, severity: String): [LogEntry!]!
        }

        type LogEntry {
            id: String!
            logName: String!
            severity: String!
            textPayload: String
            jsonPayload: String
            timestamp: String
        }

        type MonitoringQueries {
            metricTypes: [String!]!
            timeSeries(metricType: String!): [TimeSeriesPoint!]!
        }

        type TimeSeriesPoint {
            value: Float!
            timestamp: String!
        }

        type QueryHistoryResult {
            entries: [QueryHistoryEntry!]!
            totalCount: Int!
        }

        type QueryHistoryEntry {
            id: ID!
            sql: String!
            service: String!
            instance: String
            database: String
            durationMs: Int!
            rowCount: Int!
            success: Boolean!
            errorMessage: String
            executedAt: String!
        }
        """;

    private final GraphqlService graphqlService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String spannerBase;
    private final String bigqueryBase;
    private final PostgresDataSource dataSource;
    private final LocalCloudConfig config;
    private final QueryHistoryRepository queryHistoryRepository;

    public GraphQLGateway(ServiceRegistry registry, PostgresDataSource dataSource,
                          LocalCloudConfig config, QueryHistoryRepository queryHistoryRepository) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
        this.dataSource = dataSource;
        this.config = config;
        this.queryHistoryRepository = queryHistoryRepository;

        ServiceDefinition spannerDef = registry.getService("spanner");
        int spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;
        this.spannerBase = "http://localhost:" + spannerRestPort;

        ServiceDefinition bqDef = registry.getService("bigquery");
        this.bigqueryBase = bqDef != null ? "http://localhost:" + bqDef.port() : "http://localhost:9050";

        this.graphqlService = buildService();
    }

    @SuppressWarnings("unchecked")
    private GraphqlService buildService() {
        SchemaParser parser = new SchemaParser();
        SchemaGenerator generator = new SchemaGenerator();
        TypeDefinitionRegistry typeDefs = parser.parse(SCHEMA_SDL);

        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("SpannerQueries", type -> type
                    .dataFetcher("instances", env -> fetchSpannerInstances())
                    .dataFetcher("databases", env -> fetchSpannerDatabases(env.getArgument("instance")))
                    .dataFetcher("tables", env -> fetchSpannerTables(
                            env.getArgument("instance"), env.getArgument("database")))
                )
                .type("BigQueryQueries", type -> type
                    .dataFetcher("datasets", env -> fetchBigQueryDatasets())
                    .dataFetcher("tables", env -> fetchBigQueryTables(env.getArgument("datasetId")))
                )
                .type("LoggingQueries", type -> type
                    .dataFetcher("entries", env -> fetchLogEntries(
                            env.getArgument("limit"), env.getArgument("severity")))
                )
                .type("MonitoringQueries", type -> type
                    .dataFetcher("metricTypes", env -> fetchMetricTypes())
                    .dataFetcher("timeSeries", env -> fetchTimeSeries(env.getArgument("metricType")))
                )
                .type("Query", type -> type
                    .dataFetcher("spanner", env -> Map.of())
                    .dataFetcher("bigquery", env -> Map.of())
                    .dataFetcher("logging", env -> Map.of())
                    .dataFetcher("monitoring", env -> Map.of())
                    .dataFetcher("queryHistory", env -> fetchQueryHistory(
                            env.getArgument("limit"), env.getArgument("offset")))
                )
                .build();

        GraphQLSchema schema = generator.makeExecutableSchema(typeDefs, wiring);
        return GraphqlService.builder()
                .schema(schema)
                .useBlockingTaskExecutor(true)
                .build();
    }

    public GraphqlService getService() {
        return graphqlService;
    }

    // ─── Spanner DataFetchers ───────────────────────────────────────────

    private List<Map<String, Object>> fetchSpannerInstances() {
        try {
            String url = spannerBase + "/v1/projects/" + config.getProjectId() + "/instances";
            String body = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) resp.getOrDefault("instances", List.of());
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> inst : raw) {
                String fullName = (String) inst.get("name");
                String name = fullName != null ? fullName.substring(fullName.lastIndexOf('/') + 1) : "unknown";
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("displayName", inst.getOrDefault("displayName", name));
                entry.put("state", inst.getOrDefault("state", "READY"));
                entry.put("databaseCount", countDatabases(name));
                result.add(entry);
            }
            return result;
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch Spanner instances: {}", e.getMessage());
            return List.of();
        }
    }

    private int countDatabases(String instance) {
        try {
            String url = spannerBase + "/v1/projects/" + config.getProjectId() + "/instances/" + instance + "/databases";
            String body = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            List<?> dbs = (List<?>) resp.getOrDefault("databases", List.of());
            return dbs.size();
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchSpannerDatabases(String instance) {
        try {
            String url = spannerBase + "/v1/projects/" + config.getProjectId() + "/instances/" + instance + "/databases";
            String body = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) resp.getOrDefault("databases", List.of());
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> db : raw) {
                String fullName = (String) db.get("name");
                String name = fullName != null ? fullName.substring(fullName.lastIndexOf('/') + 1) : "unknown";
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("state", db.getOrDefault("state", "READY"));
                entry.put("tableCount", countTables(instance, name));
                result.add(entry);
            }
            return result;
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch Spanner databases: {}", e.getMessage());
            return List.of();
        }
    }

    private int countTables(String instance, String database) {
        try {
            String url = spannerBase + "/v1/projects/" + config.getProjectId() + "/instances/" + instance
                    + "/databases/" + database + "/ddl";
            String body = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            List<String> stmts = (List<String>) resp.getOrDefault("statements", List.of());
            return (int) stmts.stream().filter(s -> s.trim().toUpperCase().startsWith("CREATE TABLE")).count();
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchSpannerTables(String instance, String database) {
        try {
            String ddlUrl = spannerBase + "/v1/projects/" + config.getProjectId() + "/instances/" + instance
                    + "/databases/" + database + "/ddl";
            String ddlBody = proxyGet(ddlUrl);
            Map<String, Object> ddlResp = mapper.readValue(ddlBody, Map.class);
            List<String> statements = (List<String>) ddlResp.getOrDefault("statements", List.of());

            List<Map<String, Object>> tables = new ArrayList<>();
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (!trimmed.toUpperCase().startsWith("CREATE TABLE")) continue;

                Map<String, Object> parsed = parseCreateTable(trimmed);
                if (parsed != null) {
                    parsed.put("database", database);
                    parsed.put("instance", instance);
                    parsed.put("indexes", extractIndexes(trimmed));
                    tables.add(parsed);
                }
            }
            return tables;
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch Spanner tables: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> parseCreateTable(String ddl) {
        try {
            int tableIdx = ddl.toUpperCase().indexOf("CREATE TABLE");
            if (tableIdx < 0) return null;
            String afterCreate = ddl.substring(tableIdx + 12).trim();
            int firstParen = afterCreate.indexOf('(');
            if (firstParen < 0) return null;
            String tableName = afterCreate.substring(0, firstParen).trim();

            int depth = 0;
            int columnEnd = -1;
            for (int i = firstParen; i < afterCreate.length(); i++) {
                char c = afterCreate.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) { columnEnd = i; break; } }
            }
            if (columnEnd < 0) return null;

            String columnSection = afterCreate.substring(firstParen + 1, columnEnd).trim();
            List<String> columnDefs = splitTopLevel(columnSection);
            List<Map<String, String>> columns = new ArrayList<>();
            for (String colDef : columnDefs) {
                String col = colDef.trim();
                if (col.isEmpty()) continue;
                String upper = col.toUpperCase();
                if (upper.startsWith("INTERLEAVE") || upper.startsWith("CONSTRAINT") || upper.startsWith("PRIMARY KEY")) continue;
                String[] tokens = col.split("\\s+", 3);
                if (tokens.length < 2) continue;
                if (tokens[1].equalsIgnoreCase("TOKENLIST")) continue;
                columns.add(Map.of("name", tokens[0], "type", tokens[1]));
            }

            return Map.of("name", tableName, "columns", columns);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> extractIndexes(String ddl) {
        List<String> indexes = new ArrayList<>();
        for (String line : ddl.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().matches("^(?:CREATE\\s+(?:UNIQUE\\s+)?)?INDEX\\s+\\S+.*")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?i)(?:CREATE\\s+(?:UNIQUE\\s+)?)?INDEX\\s+(\\S+)").matcher(trimmed);
                if (m.find()) indexes.add(m.group(1));
            }
        }
        return indexes;
    }

    private List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < s.length()) {
            String last = s.substring(start).trim();
            if (!last.isEmpty()) parts.add(last);
        }
        return parts;
    }

    // ─── BigQuery DataFetchers ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchBigQueryDatasets() {
        try {
            String url = bigqueryBase + "/bigquery/v2/projects/" + config.getProjectId() + "/datasets";
            String body = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) resp.get("datasets");
            if (raw == null) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> ds : raw) {
                Map<String, Object> ref = (Map<String, Object>) ds.get("datasetReference");
                String id = ref != null ? (String) ref.get("datasetId") : (String) ds.get("id");
                result.add(Map.of("id", id != null ? id : "unknown", "displayName", id));
            }
            return result;
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch BigQuery datasets: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchBigQueryTables(String datasetId) {
        try {
            String url = bigqueryBase + "/bigquery/v2/projects/" + config.getProjectId() + "/datasets/"
                    + datasetId + "/tables";
            String body = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) resp.get("tables");
            if (raw == null) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> tbl : raw) {
                Map<String, Object> ref = (Map<String, Object>) tbl.get("tableReference");
                String tId = ref != null ? (String) ref.get("tableId") : null;
                if (tId == null) continue;
                List<Map<String, String>> columns = fetchBigQueryColumns(datasetId, tId);
                result.add(Map.of("name", tId, "columns", columns));
            }
            return result;
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch BigQuery tables: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> fetchBigQueryColumns(String datasetId, String tableId) {
        try {
            String url = bigqueryBase + "/bigquery/v2/projects/" + config.getProjectId() + "/datasets/"
                    + datasetId + "/tables/" + tableId;
            String body = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            Map<String, Object> schema = (Map<String, Object>) resp.get("schema");
            if (schema == null) return List.of();
            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
            if (fields == null) return List.of();
            return fields.stream().map(f -> Map.of(
                    "name", (String) f.getOrDefault("name", "?"),
                    "type", (String) f.getOrDefault("type", "STRING")
            )).collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ─── Logging DataFetchers ───────────────────────────────────────────

    private List<Map<String, Object>> fetchLogEntries(Integer limit, String severity) {
        List<Map<String, Object>> entries = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT id, log_name, severity, text_payload, json_payload, timestamp " +
            "FROM log_entries WHERE project_id = ?");
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = ?");
        }
        sql.append(" ORDER BY timestamp DESC LIMIT ?");
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, config.getProjectId());
            if (severity != null && !severity.isBlank()) {
                ps.setString(2, severity);
                ps.setInt(3, limit != null ? limit : 50);
            } else {
                ps.setInt(2, limit != null ? limit : 50);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", rs.getString("id"));
                    entry.put("logName", rs.getString("log_name"));
                    entry.put("severity", rs.getString("severity"));
                    entry.put("textPayload", rs.getString("text_payload"));
                    entry.put("jsonPayload", rs.getString("json_payload"));
                    entry.put("timestamp", rs.getString("timestamp"));
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch log entries: {}", e.getMessage());
        }
        return entries;
    }

    // ─── Monitoring DataFetchers ────────────────────────────────────────

    private List<String> fetchMetricTypes() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT DISTINCT metric_type FROM time_series WHERE project_id = ? ORDER BY metric_type")) {
            ps.setString(1, config.getProjectId());
            try (ResultSet rs = ps.executeQuery()) {
                List<String> types = new ArrayList<>();
                while (rs.next()) types.add(rs.getString("metric_type"));
                return types;
            }
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch metric types: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> fetchTimeSeries(String metricType) {
        List<Map<String, Object>> points = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT mp.double_value, mp.int_value, mp.end_time " +
                 "FROM metric_points mp JOIN time_series ts ON mp.series_id = ts.id " +
                 "WHERE ts.project_id = ? AND ts.metric_type = ? " +
                 "ORDER BY mp.end_time DESC LIMIT 100")) {
            ps.setString(1, config.getProjectId());
            ps.setString(2, metricType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double value = rs.getDouble("double_value");
                    if (rs.wasNull()) value = rs.getLong("int_value");
                    points.add(Map.of(
                        "value", value,
                        "timestamp", String.valueOf(rs.getLong("end_time"))
                    ));
                }
            }
        } catch (Exception e) {
            logger.warn("GraphQL: failed to fetch time series: {}", e.getMessage());
        }
        return points;
    }

    // ─── Query History DataFetcher ──────────────────────────────────────

    private Map<String, Object> fetchQueryHistory(Integer limit, Integer offset) {
        String projectId = config.getProjectId();
        int l = limit != null ? limit : 20;
        int o = offset != null ? offset : 0;
        List<Map<String, Object>> entries = queryHistoryRepository.list(projectId, null, l, o);
        int totalCount = queryHistoryRepository.count(projectId, null);
        return Map.of("entries", entries, "totalCount", totalCount);
    }

    // ─── HTTP Helpers ──────────────────────────────────────────────────

    private String proxyGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, BodyHandlers.ofString()).body();
    }
}
