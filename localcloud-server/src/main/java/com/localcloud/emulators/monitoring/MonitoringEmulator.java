package com.localcloud.emulators.monitoring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.google.api.MetricDescriptor;
import com.google.monitoring.v3.*;
import com.google.protobuf.Empty;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class MonitoringEmulator extends AbstractEmulator {

    private final PostgresDataSource dataSource;
    private final MonitoringService monitoringService;
    private final ObjectMapper mapper = new ObjectMapper();

    public MonitoringEmulator(PostgresDataSource dataSource) {
        super("monitoring", "Cloud Monitoring", 24080, "grpc", "MONITORING_EMULATOR_HOST");
        this.dataSource = dataSource;
        this.monitoringService = new MonitoringService();
    }

    @Override
    protected void doStart() throws Exception {
        logger.info("Cloud Monitoring emulator gRPC services ready");
    }

    @Override
    protected void doStop() {}

    @Override
    protected void doReset() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM metric_points");
            stmt.execute("DELETE FROM time_series");
        } catch (SQLException e) {
            logger.error("Failed to reset Monitoring data", e);
        }
    }

    public MonitoringService getMonitoringService() { return monitoringService; }

    public class MonitoringService extends MetricServiceGrpc.MetricServiceImplBase {

        @Override
        public void createTimeSeries(CreateTimeSeriesRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try (Connection conn = dataSource.getConnection()) {
                // Extract project_id from request name (format: projects/{project})
                String projectId = "";
                String reqName = request.getName();
                if (reqName != null && reqName.startsWith("projects/")) {
                    projectId = reqName.split("/")[1];
                }

                for (TimeSeries ts : request.getTimeSeriesList()) {
                    String metricType = ts.getMetric().getType();
                    String metricLabels = mapper.writeValueAsString(new TreeMap<>(ts.getMetric().getLabelsMap()));
                    String resourceType = ts.hasResource() ? ts.getResource().getType() : "";
                    String resourceLabels = ts.hasResource() ? mapper.writeValueAsString(new TreeMap<>(ts.getResource().getLabelsMap())) : "{}";

                    // Upsert time_series
                    String seriesId;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id FROM time_series WHERE project_id=? AND metric_type=? AND metric_labels=? AND resource_type=?")) {
                        ps.setString(1, projectId);
                        ps.setString(2, metricType);
                        ps.setString(3, metricLabels);
                        ps.setString(4, resourceType);
                        java.sql.ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            seriesId = rs.getString("id");
                        } else {
                            seriesId = UUID.randomUUID().toString();
                            try (PreparedStatement insert = conn.prepareStatement(
                                    "INSERT INTO time_series(id, project_id, project_name, metric_type, metric_labels, resource_type, resource_labels) VALUES(?,?,?,?,?,?,?)")) {
                                insert.setString(1, seriesId);
                                insert.setString(2, projectId);
                                insert.setString(3, request.getName());
                                insert.setString(4, metricType);
                                insert.setString(5, metricLabels);
                                insert.setString(6, resourceType);
                                insert.setString(7, resourceLabels);
                                insert.executeUpdate();
                            }
                        }
                    }

                    // Insert points
                    for (Point point : ts.getPointsList()) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO metric_points(id, series_id, start_time, end_time, value_type, double_value, int_value) VALUES(?,?,?,?,?,?,?)")) {
                            ps.setString(1, UUID.randomUUID().toString());
                            ps.setString(2, seriesId);
                            long startTime = point.hasInterval() && point.getInterval().hasStartTime()
                                    ? point.getInterval().getStartTime().getSeconds() : 0;
                            long endTime = point.hasInterval() && point.getInterval().hasEndTime()
                                    ? point.getInterval().getEndTime().getSeconds() : System.currentTimeMillis() / 1000;
                            ps.setLong(3, startTime);
                            ps.setLong(4, endTime);
                            if (point.hasValue()) {
                                TypedValue val = point.getValue();
                                if (val.hasDoubleValue()) {
                                    ps.setString(5, "DOUBLE");
                                    ps.setDouble(6, val.getDoubleValue());
                                    ps.setLong(7, 0);
                                } else if (val.hasInt64Value()) {
                                    ps.setString(5, "INT64");
                                    ps.setDouble(6, 0);
                                    ps.setLong(7, val.getInt64Value());
                                } else {
                                    ps.setString(5, "DOUBLE");
                                    ps.setDouble(6, 0);
                                    ps.setLong(7, 0);
                                }
                            } else {
                                ps.setString(5, "DOUBLE");
                                ps.setDouble(6, 0);
                                ps.setLong(7, 0);
                            }
                            ps.executeUpdate();
                        }
                    }
                }
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (SQLException | JsonProcessingException e) {
                logger.error("createTimeSeries failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listTimeSeries(ListTimeSeriesRequest request, StreamObserver<ListTimeSeriesResponse> responseObserver) {
            incrementRequestCount();
            try (Connection conn = dataSource.getConnection()) {
                StringBuilder sql = new StringBuilder(
                    "SELECT ts.*, mp.start_time, mp.end_time, mp.value_type, mp.double_value, mp.int_value " +
                    "FROM time_series ts LEFT JOIN metric_points mp ON ts.id = mp.series_id WHERE 1=1");
                List<Object> params = new ArrayList<>();

                // Filter by project_id from request name (format: projects/{project})
                String reqName = request.getName();
                if (reqName != null && reqName.startsWith("projects/")) {
                    String projectId = reqName.split("/")[1];
                    sql.append(" AND ts.project_id=?");
                    params.add(projectId);
                }

                String filter = request.getFilter();
                String metricType = null;
                if (filter != null && !filter.isEmpty() && filter.contains("metric.type")) {
                    // Handle both quoted and unquoted: metric.type = "foo" or metric.type = foo
                    String value = filter.replaceAll(".*metric\\.type\\s*=\\s*\"?", "").replaceAll("\".*", "").trim();
                    if (!value.isEmpty()) {
                        metricType = value;
                    }
                }
                if (metricType != null) {
                    sql.append(" AND ts.metric_type=?");
                    params.add(metricType);
                }

                if (request.hasInterval()) {
                    if (request.getInterval().hasStartTime()) {
                        sql.append(" AND mp.end_time >= ?");
                        params.add(request.getInterval().getStartTime().getSeconds());
                    }
                    if (request.getInterval().hasEndTime()) {
                        sql.append(" AND mp.end_time <= ?");
                        params.add(request.getInterval().getEndTime().getSeconds());
                    }
                }

                sql.append(" ORDER BY mp.end_time DESC");

                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    java.sql.ResultSet rs = ps.executeQuery();
                    ListTimeSeriesResponse.Builder resp = ListTimeSeriesResponse.newBuilder();
                    // Group points by series_id so each TimeSeries contains all its points
                    Map<String, TimeSeries.Builder> seriesMap = new LinkedHashMap<>();
                    while (rs.next()) {
                        String seriesId = rs.getString("id");
                        TimeSeries.Builder tsBuilder = seriesMap.get(seriesId);
                        if (tsBuilder == null) {
                            com.google.api.Metric.Builder metricBuilder = com.google.api.Metric.newBuilder()
                                    .setType(rs.getString("metric_type"));
                            // Restore metric labels from stored JSON
                            String labelsStr = rs.getString("metric_labels");
                            if (labelsStr != null && !labelsStr.isEmpty()) {
                                try {
                                    Map<String, String> labels = mapper.readValue(labelsStr, new TypeReference<Map<String, String>>() {});
                                    metricBuilder.putAllLabels(labels);
                                } catch (JsonProcessingException ignored) {}
                            }
                            // Restore resource from stored JSON
                            com.google.api.MonitoredResource.Builder resourceBuilder = com.google.api.MonitoredResource.newBuilder();
                            String resType = rs.getString("resource_type");
                            if (resType != null && !resType.isEmpty()) {
                                resourceBuilder.setType(resType);
                            }
                            String resLabelsStr = rs.getString("resource_labels");
                            if (resLabelsStr != null && !resLabelsStr.isEmpty()) {
                                try {
                                    Map<String, String> resLabels = mapper.readValue(resLabelsStr, new TypeReference<Map<String, String>>() {});
                                    resourceBuilder.putAllLabels(resLabels);
                                } catch (JsonProcessingException ignored) {}
                            }
                            tsBuilder = TimeSeries.newBuilder()
                                    .setMetric(metricBuilder.build())
                                    .setResource(resourceBuilder.build());
                            seriesMap.put(seriesId, tsBuilder);
                        }
                        // Add the point to the existing builder
                        String valueType = rs.getString("value_type");
                        TypedValue.Builder val = TypedValue.newBuilder();
                        if ("INT64".equals(valueType)) {
                            val.setInt64Value(rs.getLong("int_value"));
                        } else {
                            val.setDoubleValue(rs.getDouble("double_value"));
                        }
                        tsBuilder.addPoints(Point.newBuilder()
                                .setInterval(TimeInterval.newBuilder()
                                        .setStartTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(rs.getLong("start_time")).build())
                                        .setEndTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(rs.getLong("end_time")).build())
                                        .build())
                                .setValue(val.build())
                                .build());
                    }
                    // Convert all builders to TimeSeries protos
                    for (TimeSeries.Builder builder : seriesMap.values()) {
                        resp.addTimeSeries(builder.build());
                    }
                    responseObserver.onNext(resp.build());
                    responseObserver.onCompleted();
                }
            } catch (SQLException e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void createMetricDescriptor(CreateMetricDescriptorRequest request, StreamObserver<MetricDescriptor> responseObserver) {
            incrementRequestCount();
            // Accept and echo back - we don't enforce metric descriptors
            responseObserver.onNext(request.getMetricDescriptor());
            responseObserver.onCompleted();
        }

        @Override
        public void getMetricDescriptor(GetMetricDescriptorRequest request, StreamObserver<MetricDescriptor> responseObserver) {
            incrementRequestCount();
            // Return a basic descriptor
            responseObserver.onNext(MetricDescriptor.newBuilder()
                    .setName(request.getName())
                    .setType(request.getName().contains("/") ? request.getName().substring(request.getName().lastIndexOf('/') + 1) : request.getName())
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void listMetricDescriptors(ListMetricDescriptorsRequest request, StreamObserver<ListMetricDescriptorsResponse> responseObserver) {
            incrementRequestCount();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT DISTINCT metric_type FROM time_series")) {
                ListMetricDescriptorsResponse.Builder resp = ListMetricDescriptorsResponse.newBuilder();
                while (rs.next()) {
                    resp.addMetricDescriptors(MetricDescriptor.newBuilder()
                            .setType(rs.getString("metric_type"))
                            .build());
                }
                responseObserver.onNext(resp.build());
                responseObserver.onCompleted();
            } catch (SQLException e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void deleteMetricDescriptor(DeleteMetricDescriptorRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
