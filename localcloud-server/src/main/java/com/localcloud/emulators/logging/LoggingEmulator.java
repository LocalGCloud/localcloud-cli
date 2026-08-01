package com.localcloud.emulators.logging;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.google.logging.v2.*;
import com.google.protobuf.Empty;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.logging.LoggingSinkRepository;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class LoggingEmulator extends AbstractEmulator {

    private final PostgresDataSource dataSource;
    private final LoggingService loggingService;
    private final ConfigService configService;
    private final LoggingSinkRepository sinkRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public LoggingEmulator(PostgresDataSource dataSource) {
        super("logging", "Cloud Logging", 24080, "grpc", "LOGGING_EMULATOR_HOST");
        this.dataSource = dataSource;
        this.sinkRepository = new LoggingSinkRepository(dataSource);
        this.loggingService = new LoggingService();
        this.configService = new ConfigService();
    }

    @Override
    protected void doStart() throws Exception {
        logger.info("Cloud Logging emulator gRPC services ready");
    }

    @Override
    protected void doStop() {}

    @Override
    protected void doReset() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM log_entries");
        } catch (SQLException e) {
            logger.error("Failed to reset Logging data", e);
        }
    }

    public LoggingService getLoggingService() { return loggingService; }

    public ConfigService getConfigService() { return configService; }

    private static final java.util.Map<String, Integer> SEVERITY_ORDINALS = java.util.Map.of(
            "DEFAULT", 0, "DEBUG", 100, "INFO", 200, "NOTICE", 300,
            "WARNING", 400, "ERROR", 500, "CRITICAL", 600, "ALERT", 700
    );
    // Map.of supports max 10 entries; add EMERGENCY separately
    private static final int EMERGENCY_ORDINAL = 800;

    private boolean matchesExclusionFilter(String filter, String severity, String textPayload, String resourceType) {
        if (filter == null || filter.isEmpty()) return false;
        if (filter.contains("severity>=") && severity != null) {
            String sevFilter = filter.replaceAll(".*severity>=\\s*", "").trim();
            var atOrAbove = severitiesAtOrAbove(sevFilter);
            return atOrAbove.contains(severity);
        }
        if (filter.contains("resource.type") && resourceType != null) {
            String typeFilter = filter.replaceAll(".*resource\\.type\\s*=\\s*\"([^\"]+)\".*", "$1");
            return typeFilter.equals(resourceType);
        }
        if (filter.contains("textPayload:") && textPayload != null) {
            String payloadFilter = filter.replaceAll(".*textPayload:\\s*\"([^\"]+)\".*", "$1");
            return textPayload.contains(payloadFilter);
        }
        return false;
    }

    private static List<String> severitiesAtOrAbove(String level) {
        int threshold = level.equals("EMERGENCY") ? EMERGENCY_ORDINAL
                : SEVERITY_ORDINALS.getOrDefault(level, 0);
        List<String> result = new ArrayList<>();
        for (var entry : SEVERITY_ORDINALS.entrySet()) {
            if (entry.getValue() >= threshold) {
                result.add(entry.getKey());
            }
        }
        if (EMERGENCY_ORDINAL >= threshold) {
            result.add("EMERGENCY");
        }
        return result;
    }

    public class LoggingService extends LoggingServiceV2Grpc.LoggingServiceV2ImplBase {

        @Override
        public void writeLogEntries(WriteLogEntriesRequest request, StreamObserver<WriteLogEntriesResponse> responseObserver) {
            incrementRequestCount();
            try (Connection conn = dataSource.getConnection()) {
                // Extract project_id from logName (format: projects/{project}/logs/{log})
                String requestLogName = request.getLogName();
                String projectId = "";
                if (requestLogName != null && requestLogName.startsWith("projects/")) {
                    projectId = requestLogName.split("/")[1];
                }

                for (LogEntry entry : request.getEntriesList()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO log_entries(id, project_id, log_name, resource_type, resource_labels, severity, text_payload, json_payload, timestamp, insert_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                        String id = UUID.randomUUID().toString();
                        ps.setString(1, id);
                        // Derive project_id from entry logName if available, else from request logName
                        String entryLogName = entry.getLogName().isEmpty() ? requestLogName : entry.getLogName();
                        String entryProjectId = projectId;
                        if (!entry.getLogName().isEmpty() && entry.getLogName().startsWith("projects/")) {
                            entryProjectId = entry.getLogName().split("/")[1];
                        }
                        ps.setString(2, entryProjectId);
                        String logName = entryLogName;
                        ps.setString(3, logName);
                        String resourceType = "";
                        String resourceLabels = "{}";
                        if (entry.hasResource()) {
                            resourceType = entry.getResource().getType();
                            resourceLabels = mapper.writeValueAsString(new TreeMap<>(entry.getResource().getLabelsMap()));
                        } else if (request.hasResource()) {
                            resourceType = request.getResource().getType();
                            resourceLabels = mapper.writeValueAsString(new TreeMap<>(request.getResource().getLabelsMap()));
                        }
                        ps.setString(4, resourceType);
                        ps.setString(5, resourceLabels);
                        ps.setString(6, entry.getSeverity().name());
                        ps.setString(7, entry.getTextPayload());
                        ps.setString(8, entry.hasJsonPayload() ? entry.getJsonPayload().toString() : "");
                        long ts = entry.hasTimestamp()
                                ? entry.getTimestamp().getSeconds() * 1000 + entry.getTimestamp().getNanos() / 1000000
                                : System.currentTimeMillis();
                        ps.setLong(9, ts);
                        ps.setString(10, entry.getInsertId().isEmpty() ? id : entry.getInsertId());
                        ps.executeUpdate();
                    }
                }
                responseObserver.onNext(WriteLogEntriesResponse.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (SQLException | JsonProcessingException e) {
                logger.error("writeLogEntries failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listLogEntries(ListLogEntriesRequest request, StreamObserver<ListLogEntriesResponse> responseObserver) {
            incrementRequestCount();
            try (Connection conn = dataSource.getConnection()) {
                StringBuilder sql = new StringBuilder("SELECT * FROM log_entries WHERE 1=1");
                List<Object> params = new ArrayList<>();

                // Filter by project_id from resourceNames (format: projects/{project})
                if (request.getResourceNamesCount() > 0) {
                    String resourceName = request.getResourceNames(0);
                    if (resourceName.startsWith("projects/")) {
                        String projectId = resourceName.split("/")[1];
                        sql.append(" AND project_id=?");
                        params.add(projectId);
                    }
                }

                String filter = request.getFilter();
                if (filter != null && !filter.isEmpty()) {
                    // Simple filter support: logName="xxx"
                    if (filter.contains("logName=")) {
                        String logName = filter.split("logName=")[1].trim().replace("\"", "").split("\\s")[0];
                        sql.append(" AND log_name=?");
                        params.add(logName);
                    }
                    if (filter.contains("severity>=")) {
                        String sev = filter.split("severity>=")[1].trim().replace("\"", "").split("\\s")[0];
                        List<String> atOrAbove = severitiesAtOrAbove(sev);
                        if (!atOrAbove.isEmpty()) {
                            sql.append(" AND severity IN (");
                            for (int i = 0; i < atOrAbove.size(); i++) {
                                sql.append(i == 0 ? "?" : ",?");
                                params.add(atOrAbove.get(i));
                            }
                            sql.append(")");
                        }
                    }
                }
                sql.append(" ORDER BY timestamp DESC");
                int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 100;
                sql.append(" LIMIT ").append(pageSize);

                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    java.sql.ResultSet rs = ps.executeQuery();

                    // Load exclusion filters for this project
                    List<String> exclusionFilters = new ArrayList<>();
                    String exclusionProjectId = request.getResourceNamesCount() > 0
                            ? request.getResourceNames(0).split("/")[1] : "";
                    if (!exclusionProjectId.isEmpty()) {
                        try (PreparedStatement efPs = conn.prepareStatement(
                                "SELECT filter FROM log_exclusion_filters WHERE project_id = ? AND disabled = FALSE")) {
                            efPs.setString(1, exclusionProjectId);
                            try (var efRs = efPs.executeQuery()) {
                                while (efRs.next()) exclusionFilters.add(efRs.getString("filter"));
                            }
                        }
                    }

                    ListLogEntriesResponse.Builder resp = ListLogEntriesResponse.newBuilder();
                    while (rs.next()) {
                        LogEntry.Builder entry = LogEntry.newBuilder()
                                .setLogName(rs.getString("log_name"))
                                .setTextPayload(rs.getString("text_payload") != null ? rs.getString("text_payload") : "")
                                .setInsertId(rs.getString("insert_id"))
                                .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                                        .setSeconds(rs.getLong("timestamp") / 1000)
                                        .build());
                        String sev = rs.getString("severity");
                        if (sev != null) {
                            try {
                                entry.setSeverity(com.google.logging.type.LogSeverity.valueOf(sev));
                            } catch (IllegalArgumentException ignored) {}
                        }

                        // Apply exclusion filters
                        boolean excluded = false;
                        String textPayload = entry.getTextPayload();
                        for (String ef : exclusionFilters) {
                            if (matchesExclusionFilter(ef, sev, textPayload, rs.getString("resource_type"))) {
                                excluded = true;
                                break;
                            }
                        }
                        if (!excluded) {
                            resp.addEntries(entry.build());
                        }
                    }
                    responseObserver.onNext(resp.build());
                    responseObserver.onCompleted();
                }
            } catch (SQLException e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listLogs(ListLogsRequest request, StreamObserver<ListLogsResponse> responseObserver) {
            incrementRequestCount();
            try (Connection conn = dataSource.getConnection()) {
                // Filter by project_id from parent (format: projects/{project})
                String sql = "SELECT DISTINCT log_name FROM log_entries";
                String parent = request.getParent();
                String projectId = null;
                if (parent != null && parent.startsWith("projects/")) {
                    projectId = parent.split("/")[1];
                    sql += " WHERE project_id=?";
                }
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    if (projectId != null) {
                        pstmt.setString(1, projectId);
                    }
                    try (java.sql.ResultSet rs = pstmt.executeQuery()) {
                        ListLogsResponse.Builder resp = ListLogsResponse.newBuilder();
                        while (rs.next()) {
                            resp.addLogNames(rs.getString("log_name"));
                        }
                        responseObserver.onNext(resp.build());
                        responseObserver.onCompleted();
                    }
                }
            } catch (SQLException e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void deleteLog(DeleteLogRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM log_entries WHERE log_name=?")) {
                ps.setString(1, request.getLogName());
                ps.executeUpdate();
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (SQLException e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }

    /**
     * gRPC ConfigServiceV2 implementation for logging sink CRUD.
     * Delegates to {@link LoggingSinkRepository} for persistence.
     */
    public class ConfigService extends ConfigServiceV2Grpc.ConfigServiceV2ImplBase {

        @Override
        public void createSink(CreateSinkRequest request, StreamObserver<LogSink> responseObserver) {
            incrementRequestCount();
            try {
                LogSink sink = request.getSink();
                // parent: "projects/{project}" or "projects/{project}/logs/{log}"
                String parent = request.getParent();
                String projectId = extractProjectId(parent);

                String result = sinkRepository.create(projectId, sink.getName(), sink.getDestination());
                LogSink created = parseSinkJson(result);
                responseObserver.onNext(created);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("createSink failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void getSink(GetSinkRequest request, StreamObserver<LogSink> responseObserver) {
            incrementRequestCount();
            try {
                // sinkName: "projects/{project}/sinks/{sink}"
                String fullSinkName = request.getSinkName();
                String[] parts = fullSinkName.split("/");
                if (parts.length < 4) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid sink name: " + fullSinkName)
                            .asRuntimeException());
                    return;
                }
                String projectId = parts[1];
                String sinkId = parts[3];

                String result = sinkRepository.find(projectId, sinkId);
                if (result == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Sink not found: " + fullSinkName)
                            .asRuntimeException());
                    return;
                }

                LogSink logSink = parseSinkJson(result);
                responseObserver.onNext(logSink);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("getSink failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void updateSink(UpdateSinkRequest request, StreamObserver<LogSink> responseObserver) {
            incrementRequestCount();
            try {
                LogSink sink = request.getSink();
                // sinkName: "projects/{project}/sinks/{sink}"
                String sinkName = request.getSinkName();
                String[] parts = sinkName.split("/");
                if (parts.length < 4) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid sink name: " + sinkName)
                            .asRuntimeException());
                    return;
                }
                String projectId = parts[1];
                String sinkId = parts[3];

                String destination = sink.getDestination();
                String result = sinkRepository.create(projectId, sinkId,
                        destination.isEmpty() ? null : destination);
                if (result == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Sink not found: " + sinkName)
                            .asRuntimeException());
                    return;
                }

                LogSink updatedSink = parseSinkJson(result);
                responseObserver.onNext(updatedSink);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("updateSink failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void deleteSink(DeleteSinkRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try {
                // sinkName: "projects/{project}/sinks/{sink}"
                String sinkName = request.getSinkName();
                String[] parts = sinkName.split("/");
                if (parts.length < 4) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Invalid sink name: " + sinkName)
                            .asRuntimeException());
                    return;
                }
                String projectId = parts[1];
                String sinkId = parts[3];

                boolean deleted = sinkRepository.delete(projectId, sinkId);
                if (!deleted) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Sink not found: " + sinkName)
                            .asRuntimeException());
                    return;
                }

                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("deleteSink failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listSinks(ListSinksRequest request, StreamObserver<ListSinksResponse> responseObserver) {
            incrementRequestCount();
            try {
                // parent: "projects/{project}"
                String projectId = extractProjectId(request.getParent());
                List<String> results = sinkRepository.list(projectId);

                ListSinksResponse.Builder builder = ListSinksResponse.newBuilder();
                for (String json : results) {
                    builder.addSinks(parseSinkJson(json));
                }
                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("listSinks failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        private String extractProjectId(String parent) {
            if (parent == null || parent.isEmpty()) return "local-project";
            if (parent.startsWith("projects/")) {
                String[] parts = parent.split("/");
                if (parts.length >= 2) return parts[1];
            }
            return "local-project";
        }

        private LogSink parseSinkJson(String json) throws Exception {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
            LogSink.Builder builder = LogSink.newBuilder();
            if (node.has("name")) builder.setName(node.get("name").asText());
            if (node.has("destination")) builder.setDestination(node.get("destination").asText());
            if (node.has("writerIdentity")) builder.setWriterIdentity(node.get("writerIdentity").asText());
            return builder.build();
        }
    }
}
