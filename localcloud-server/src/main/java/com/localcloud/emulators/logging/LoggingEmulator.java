package com.localcloud.emulators.logging;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.logging.v2.*;
import com.google.protobuf.Empty;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class LoggingEmulator extends AbstractEmulator {

    private final PostgresDataSource dataSource;
    private final LoggingService loggingService;

    public LoggingEmulator(PostgresDataSource dataSource) {
        super("logging", "Cloud Logging", 8080, "grpc", "LOGGING_EMULATOR_HOST");
        this.dataSource = dataSource;
        this.loggingService = new LoggingService();
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

    private static final java.util.Map<String, Integer> SEVERITY_ORDINALS = java.util.Map.of(
            "DEFAULT", 0, "DEBUG", 100, "INFO", 200, "NOTICE", 300,
            "WARNING", 400, "ERROR", 500, "CRITICAL", 600, "ALERT", 700
    );
    // Map.of supports max 10 entries; add EMERGENCY separately
    private static final int EMERGENCY_ORDINAL = 800;

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
                for (LogEntry entry : request.getEntriesList()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO log_entries(id, log_name, resource_type, resource_labels, severity, text_payload, json_payload, timestamp, insert_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?)")) {
                        String id = UUID.randomUUID().toString();
                        ps.setString(1, id);
                        String logName = entry.getLogName().isEmpty() ? request.getLogName() : entry.getLogName();
                        ps.setString(2, logName);
                        String resourceType = "";
                        String resourceLabels = "{}";
                        if (entry.hasResource()) {
                            resourceType = entry.getResource().getType();
                            resourceLabels = entry.getResource().getLabelsMap().toString();
                        } else if (request.hasResource()) {
                            resourceType = request.getResource().getType();
                            resourceLabels = request.getResource().getLabelsMap().toString();
                        }
                        ps.setString(3, resourceType);
                        ps.setString(4, resourceLabels);
                        ps.setString(5, entry.getSeverity().name());
                        ps.setString(6, entry.getTextPayload());
                        ps.setString(7, entry.hasJsonPayload() ? entry.getJsonPayload().toString() : "");
                        long ts = entry.hasTimestamp()
                                ? entry.getTimestamp().getSeconds() * 1000 + entry.getTimestamp().getNanos() / 1000000
                                : System.currentTimeMillis();
                        ps.setLong(8, ts);
                        ps.setString(9, entry.getInsertId().isEmpty() ? id : entry.getInsertId());
                        ps.executeUpdate();
                    }
                }
                responseObserver.onNext(WriteLogEntriesResponse.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (SQLException e) {
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
                        resp.addEntries(entry.build());
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
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT DISTINCT log_name FROM log_entries")) {
                ListLogsResponse.Builder resp = ListLogsResponse.newBuilder();
                while (rs.next()) {
                    resp.addLogNames(rs.getString("log_name"));
                }
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
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
}
