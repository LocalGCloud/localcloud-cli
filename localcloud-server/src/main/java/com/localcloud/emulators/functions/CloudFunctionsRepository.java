package com.localcloud.emulators.functions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.google.cloud.functions.v2.Function;
import com.localcloud.persistence.PostgresDataSource;

public class CloudFunctionsRepository {
    private final PostgresDataSource dataSource;

    public CloudFunctionsRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    private void createSchema() {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            String jsonType = conn.getMetaData().getURL().contains(":h2:") ? "TEXT" : "JSONB";
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS cloud_functions (
                        project_id VARCHAR(255) NOT NULL,
                        location_id VARCHAR(255) NOT NULL,
                        function_id VARCHAR(255) NOT NULL,
                        runtime VARCHAR(128) DEFAULT '',
                        entry_point VARCHAR(255) DEFAULT '',
                        build_config %s DEFAULT '{}',
                        service_config %s DEFAULT '{}',
                        event_trigger %s DEFAULT '{}',
                        state VARCHAR(32) DEFAULT 'ACTIVE',
                        function_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location_id, function_id)
                    )
                    """.formatted(jsonType, jsonType, jsonType));
            stmt.execute("ALTER TABLE cloud_functions ADD COLUMN IF NOT EXISTS trigger_event_type VARCHAR(256)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize Cloud Functions schema", e);
        }
    }

    public boolean exists(String projectId, String locationId, String functionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM cloud_functions WHERE project_id=? AND location_id=? AND function_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, functionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void create(String projectId, String locationId, String functionId, Function function) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO cloud_functions
                     (project_id, location_id, function_id, runtime, entry_point, state, function_proto)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, functionId);
            ps.setString(4, function.hasBuildConfig() ? function.getBuildConfig().getRuntime() : "");
            ps.setString(5, function.hasBuildConfig() ? function.getBuildConfig().getEntryPoint() : "");
            ps.setString(6, function.getState().name());
            ps.setBytes(7, function.toByteArray());
            ps.executeUpdate();
        }
    }

    public Function get(String projectId, String locationId, String functionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT function_proto FROM cloud_functions WHERE project_id=? AND location_id=? AND function_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, functionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? parse(rs.getBytes("function_proto")) : null;
            }
        }
    }

    public List<Function> list(String projectId, String locationId) throws SQLException {
        List<Function> functions = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT function_proto FROM cloud_functions
                     WHERE project_id=? AND location_id=?
                     ORDER BY function_id
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) functions.add(parse(rs.getBytes("function_proto")));
            }
        }
        return functions;
    }

    public boolean update(String projectId, String locationId, String functionId, Function function) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE cloud_functions
                     SET runtime=?, entry_point=?, state=?, function_proto=?, updated_at=CURRENT_TIMESTAMP
                     WHERE project_id=? AND location_id=? AND function_id=?
                     """)) {
            ps.setString(1, function.hasBuildConfig() ? function.getBuildConfig().getRuntime() : "");
            ps.setString(2, function.hasBuildConfig() ? function.getBuildConfig().getEntryPoint() : "");
            ps.setString(3, function.getState().name());
            ps.setBytes(4, function.toByteArray());
            ps.setString(5, projectId);
            ps.setString(6, locationId);
            ps.setString(7, functionId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean delete(String projectId, String locationId, String functionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM cloud_functions WHERE project_id=? AND location_id=? AND function_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, functionId);
            return ps.executeUpdate() > 0;
        }
    }

    private static Function parse(byte[] bytes) throws SQLException {
        try {
            return Function.parseFrom(bytes);
        } catch (Exception e) {
            throw new SQLException("Failed to parse stored function", e);
        }
    }
}
