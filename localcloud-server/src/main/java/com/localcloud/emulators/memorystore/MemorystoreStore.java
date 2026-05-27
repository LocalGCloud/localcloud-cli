package com.localcloud.emulators.memorystore;

import com.localcloud.persistence.PostgresDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MemorystoreStore {

    private final PostgresDataSource dataSource;

    public MemorystoreStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> createInstance(String project, String instanceId, String displayName,
                                              String tier, String engine, String redisVersion,
                                              int port, int memorySizeGb) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO memorystore_instances (project_id, instance_id, display_name, tier, engine, redis_version, port, memory_size_gb, state, host) " +
                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', 'localhost')")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            ps.setString(3, displayName != null ? displayName : instanceId);
            ps.setString(4, tier != null ? tier : "BASIC");
            ps.setString(5, engine != null ? engine : "REDIS");
            ps.setString(6, redisVersion != null ? redisVersion : "7_0");
            ps.setInt(7, port > 0 ? port : 6379);
            ps.setInt(8, memorySizeGb > 0 ? memorySizeGb : 1);
            ps.executeUpdate();
        }
        return getInstance(project, instanceId);
    }

    public Map<String, Object> getInstance(String project, String instanceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT project_id, instance_id, display_name, tier, engine, redis_version, port, memory_size_gb, state, host, labels_json, created_at " +
                 "FROM memorystore_instances WHERE project_id = ? AND instance_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? instanceMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listInstances(String project) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT project_id, instance_id, display_name, tier, engine, redis_version, port, memory_size_gb, state, host, labels_json, created_at " +
                 "FROM memorystore_instances WHERE project_id = ? ORDER BY instance_id")) {
            ps.setString(1, project);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(instanceMap(rs));
            }
        }
        return result;
    }

    public boolean deleteInstance(String project, String instanceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM memorystore_instances WHERE project_id = ? AND instance_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            return ps.executeUpdate() > 0;
        }
    }

    public void clearAll() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM memorystore_instances");
        } catch (SQLException ignored) {
        }
    }

    private Map<String, Object> instanceMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("instance_id", rs.getString("instance_id"));
        row.put("display_name", rs.getString("display_name"));
        row.put("tier", rs.getString("tier"));
        row.put("engine", rs.getString("engine"));
        row.put("redis_version", rs.getString("redis_version"));
        row.put("port", rs.getInt("port"));
        row.put("memory_size_gb", rs.getInt("memory_size_gb"));
        row.put("state", rs.getString("state"));
        row.put("host", rs.getString("host"));
        row.put("labels_json", rs.getString("labels_json"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }
}
