package com.localcloud.emulators.monitoring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    public MonitoringEmulator(PostgresDataSource dataSource) {
        super("monitoring", "Cloud Monitoring", 8080, "grpc", "MONITORING_EMULATOR_HOST");
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
            stmt.execute("DELETE FROM time_series");
            stmt.execute("DELETE FROM metric_points");
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
                for (TimeSeries ts : request.getTimeSeriesList()) {
                    String metricType = ts.getMetric().getType();
                    String metricLabels = ts.getMetric().getLabelsMap().toString();
                    String resourceType = ts.hasResource() ? ts.getResource().getType() : "";
                    String resourceLabels = ts.hasResource() ? ts.getResource().getLabelsMap().toString() : "{}";

                    // Upsert time_series
                    String seriesId;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id FROM time_series WHERE metric_type=? AND metric_labels=? AND resource_type=?")) {
                        ps.setString(1, metricType);
                        ps.setString(2, metricLabels);
                        ps.setString(3, resourceType);
                        java.sql.ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            seriesId = rs.getString("id");
                        } else {
                            seriesId = UUID.randomUUID().toString();
                            try (PreparedStatement insert = conn.prepareStatement(
                                    "INSERT INTO time_series(id, project_name, metric_type, metric_labels, resource_type, resource_labels) VALUES(?,?,?,?,?,?)")) {
                                insert.setString(1, seriesId);
                                insert.setString(2, request.getName());
                                insert.setString(3, metricType);
                                insert.setString(4, metricLabels);
                                insert.setString(5, resourceType);
                                insert.setString(6, resourceLabels);
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
            } catch (SQLException e) {
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

                String filter = request.getFilter();
                if (filter != null && !filter.isEmpty()) {
                    // Simple filter: metric.type = "xxx"
                    if (filter.contains("metric.type")) {
                        String metricType = filter.split("\"")[1];
                        sql.append(" AND ts.metric_type=?");
                        params.add(metricType);
                    }
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
                    // Simplified: each row becomes a time series with one point
                    while (rs.next()) {
                        com.google.api.Metric.Builder metricBuilder = com.google.api.Metric.newBuilder()
                                .setType(rs.getString("metric_type"));
                        // Restore metric labels from stored string (format: {key=value, key2=value2})
                        String labelsStr = rs.getString("metric_labels");
                        if (labelsStr != null && labelsStr.length() > 2) {
                            String inner = labelsStr.substring(1, labelsStr.length() - 1); // strip { }
                            if (!inner.isEmpty()) {
                                for (String pair : inner.split(", ")) {
                                    int eq = pair.indexOf('=');
                                    if (eq > 0) {
                                        metricBuilder.putLabels(pair.substring(0, eq), pair.substring(eq + 1));
                                    }
                                }
                            }
                        }
                        TimeSeries.Builder tsBuilder = TimeSeries.newBuilder()
                                .setMetric(metricBuilder.build());
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
                        resp.addTimeSeries(tsBuilder.build());
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
