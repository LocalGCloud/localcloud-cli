package com.localcloud.emulators.bigtable;

import com.localcloud.persistence.PostgresDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BigtableStore {

    private final PostgresDataSource dataSource;

    public BigtableStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> createInstance(String project, String instanceId, String displayName,
                                              String instanceType, String clustersJson) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO bigtable_instances (project_id, instance_id, display_name, instance_type, state, clusters_json) " +
                 "VALUES (?, ?, ?, ?, 'READY', ?)")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            ps.setString(3, displayName != null ? displayName : instanceId);
            ps.setString(4, instanceType != null ? instanceType : "PRODUCTION");
            ps.setString(5, clustersJson != null ? clustersJson : "[]");
            ps.executeUpdate();
        }
        return getInstance(project, instanceId);
    }

    public Map<String, Object> getInstance(String project, String instanceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT project_id, instance_id, display_name, instance_type, state, clusters_json, labels_json, created_at " +
                 "FROM bigtable_instances WHERE project_id = ? AND instance_id = ?")) {
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
                 "SELECT project_id, instance_id, display_name, instance_type, state, clusters_json, labels_json, created_at " +
                 "FROM bigtable_instances WHERE project_id = ? ORDER BY instance_id")) {
            ps.setString(1, project);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(instanceMap(rs));
            }
        }
        return result;
    }

    public boolean deleteInstance(String project, String instanceId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM bigtable_tables WHERE project_id = ? AND instance_id = ?")) {
                    ps.setString(1, project);
                    ps.setString(2, instanceId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM bigtable_instances WHERE project_id = ? AND instance_id = ?")) {
                    ps.setString(1, project);
                    ps.setString(2, instanceId);
                    boolean deleted = ps.executeUpdate() > 0;
                    conn.commit();
                    return deleted;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public Map<String, Object> createTable(String project, String instanceId, String tableId,
                                          String columnFamiliesJson, String granularity) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO bigtable_tables (project_id, instance_id, table_id, column_families_json, granularity) " +
                 "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            ps.setString(3, tableId);
            ps.setString(4, columnFamiliesJson != null ? columnFamiliesJson : "[]");
            ps.setString(5, granularity != null ? granularity : "MILLIS");
            ps.executeUpdate();
        }
        return getTable(project, instanceId, tableId);
    }

    public Map<String, Object> getTable(String project, String instanceId, String tableId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT project_id, instance_id, table_id, column_families_json, granularity, created_at " +
                 "FROM bigtable_tables WHERE project_id = ? AND instance_id = ? AND table_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            ps.setString(3, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? tableMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listTables(String project, String instanceId) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT project_id, instance_id, table_id, column_families_json, granularity, created_at " +
                 "FROM bigtable_tables WHERE project_id = ? AND instance_id = ? ORDER BY table_id")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(tableMap(rs));
            }
        }
        return result;
    }

    public boolean deleteTable(String project, String instanceId, String tableId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM bigtable_tables WHERE project_id = ? AND instance_id = ? AND table_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, instanceId);
            ps.setString(3, tableId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean modifyColumnFamilies(String project, String instanceId, String tableId,
                                       String columnFamiliesJson) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE bigtable_tables SET column_families_json = ? " +
                 "WHERE project_id = ? AND instance_id = ? AND table_id = ?")) {
            ps.setString(1, columnFamiliesJson);
            ps.setString(2, project);
            ps.setString(3, instanceId);
            ps.setString(4, tableId);
            return ps.executeUpdate() > 0;
        }
    }

    public void clearAll() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM bigtable_tables");
            stmt.execute("DELETE FROM bigtable_instances");
        } catch (SQLException ignored) {
        }
    }

    private Map<String, Object> instanceMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("instance_id", rs.getString("instance_id"));
        row.put("display_name", rs.getString("display_name"));
        row.put("instance_type", rs.getString("instance_type"));
        row.put("state", rs.getString("state"));
        row.put("clusters_json", rs.getString("clusters_json"));
        row.put("labels_json", rs.getString("labels_json"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    private Map<String, Object> tableMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("instance_id", rs.getString("instance_id"));
        row.put("table_id", rs.getString("table_id"));
        row.put("column_families_json", rs.getString("column_families_json"));
        row.put("granularity", rs.getString("granularity"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }
}
