package com.localcloud.emulators.cloudsql;

import com.localcloud.persistence.PostgresDataSource;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PostgreSQL metadata store for Cloud SQL Admin API resources.
 */
public class CloudSqlStore {

    private final PostgresDataSource dataSource;

    public CloudSqlStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<String, Object> createInstance(String project, String instance, String region,
                                              String databaseVersion, String tier, String settingsJson) throws SQLException {
        String backendType = databaseVersion != null && databaseVersion.startsWith("MYSQL")
                ? "OPENHALO_MYSQL_COMPAT" : "POSTGRES";
        String connectionName = project + ":" + region + ":" + instance;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudsql_instances (project_id, instance_id, region, database_version, tier, state, backend_type, connection_name, settings_json) " +
                             "VALUES (?, ?, ?, ?, ?, 'RUNNABLE', ?, ?, ?)")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            ps.setString(3, region);
            ps.setString(4, databaseVersion);
            ps.setString(5, tier);
            ps.setString(6, backendType);
            ps.setString(7, connectionName);
            ps.setString(8, settingsJson != null ? settingsJson : "{}");
            ps.executeUpdate();
        }
        return getInstance(project, instance);
    }

    public Map<String, Object> updateInstance(String project, String instance, String tier, String settingsJson) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE cloudsql_instances SET tier = ?, settings_json = ? WHERE project_id = ? AND instance_id = ?")) {
            ps.setString(1, tier);
            ps.setString(2, settingsJson != null ? settingsJson : "{}");
            ps.setString(3, project);
            ps.setString(4, instance);
            ps.executeUpdate();
        }
        return getInstance(project, instance);
    }

    public Map<String, Object> getInstance(String project, String instance) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, instance_id, region, database_version, tier, state, backend_type, connection_name, settings_json, created_at " +
                             "FROM cloudsql_instances WHERE project_id = ? AND instance_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? instanceMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listInstances(String project) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, instance_id, region, database_version, tier, state, backend_type, connection_name, settings_json, created_at " +
                             "FROM cloudsql_instances WHERE project_id = ? ORDER BY instance_id")) {
            ps.setString(1, project);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(instanceMap(rs));
            }
        }
        return result;
    }

    public boolean deleteInstance(String project, String instance) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM cloudsql_users WHERE project_id = ? AND instance_id = ?")) {
                    ps.setString(1, project);
                    ps.setString(2, instance);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM cloudsql_databases WHERE project_id = ? AND instance_id = ?")) {
                    ps.setString(1, project);
                    ps.setString(2, instance);
                    ps.executeUpdate();
                }
                boolean deleted;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM cloudsql_instances WHERE project_id = ? AND instance_id = ?")) {
                    ps.setString(1, project);
                    ps.setString(2, instance);
                    deleted = ps.executeUpdate() > 0;
                }
                conn.commit();
                return deleted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public Map<String, Object> createDatabase(String project, String instance, String database,
                                              String charset, String collation) throws SQLException {
        String physical = physicalName(project, instance, database);
        String databaseVersion = getDatabaseVersion(project, instance);
        try (Connection conn = dataSource.getConnection()) {
            // Idempotent: check first, then insert (H2 doesn't support ON CONFLICT DO NOTHING)
            Map<String, Object> existing = getDatabase(project, instance, database);
            if (existing == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cloudsql_databases (project_id, instance_id, database_name, charset, \"collation\", physical_name) " +
                                "VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, project);
                    ps.setString(2, instance);
                    ps.setString(3, database);
                    ps.setString(4, charset != null ? charset : "UTF8");
                    ps.setString(5, collation != null ? collation : "");
                    ps.setString(6, physical);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    // Race condition: another thread inserted between check and insert.
                    // Both H2 and PostgreSQL report constraint violations so treat as idempotent.
                    if (!isDuplicateKeyError(e)) throw e;
                }
            }
            if (databaseVersion != null && databaseVersion.startsWith("POSTGRES")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE DATABASE " + physical);
                } catch (SQLException e) {
                    // Database already exists — ignore duplicate
                    if (!e.getMessage().contains("already exists")) throw e;
                }
            }
        }
        return getDatabase(project, instance, database);
    }

    public Map<String, Object> getDatabase(String project, String instance, String database) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, instance_id, database_name, charset, \"collation\", physical_name, created_at " +
                             "FROM cloudsql_databases WHERE project_id = ? AND instance_id = ? AND database_name = ?")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            ps.setString(3, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? databaseMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listDatabases(String project, String instance) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, instance_id, database_name, charset, \"collation\", physical_name, created_at " +
                             "FROM cloudsql_databases WHERE project_id = ? AND instance_id = ? ORDER BY database_name")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(databaseMap(rs));
            }
        }
        return result;
    }

    public boolean deleteDatabase(String project, String instance, String database) throws SQLException {
        String physical = getPhysicalName(project, instance, database);
        String databaseVersion = getDatabaseVersion(project, instance);
        try (Connection conn = dataSource.getConnection()) {
            if (physical != null && databaseVersion != null && databaseVersion.startsWith("POSTGRES")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP DATABASE IF EXISTS " + physical);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM cloudsql_databases WHERE project_id = ? AND instance_id = ? AND database_name = ?")) {
                ps.setString(1, project);
                ps.setString(2, instance);
                ps.setString(3, database);
                boolean deleted = ps.executeUpdate() > 0;
                if (deleted) insertOperation(project, instance, "DELETE_DATABASE", "projects/" + project + "/instances/" + instance + "/databases/" + database, "{}");
                return deleted;
            }
        }
    }

    public Map<String, Object> createUser(String project, String instance, String user, String host, String password) throws Exception {
        String passwordHash = password == null ? null : HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudsql_users (project_id, instance_id, user_name, host, password_hash) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            ps.setString(3, user);
            ps.setString(4, host != null && !host.isBlank() ? host : "%");
            ps.setString(5, passwordHash);
            ps.executeUpdate();
        }
        return getUser(project, instance, user, host != null && !host.isBlank() ? host : "%");
    }

    public Map<String, Object> getUser(String project, String instance, String user, String host) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, instance_id, user_name, host, password_hash, created_at FROM cloudsql_users " +
                             "WHERE project_id = ? AND instance_id = ? AND user_name = ? AND host = ?")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            ps.setString(3, user);
            ps.setString(4, host);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? userMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listUsers(String project, String instance) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, instance_id, user_name, host, password_hash, created_at FROM cloudsql_users " +
                             "WHERE project_id = ? AND instance_id = ? ORDER BY user_name")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(userMap(rs));
            }
        }
        return result;
    }

    public String insertOperation(String project, String instance, String type, String targetLink, String errorJson) throws SQLException {
        String id = "operation-" + UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudsql_operations (operation_id, project_id, instance_id, operation_type, status, target_link, error_json) " +
                             "VALUES (?, ?, ?, ?, 'DONE', ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, project);
            ps.setString(3, instance);
            ps.setString(4, type);
            ps.setString(5, targetLink);
            ps.setString(6, errorJson != null ? errorJson : "{}");
            ps.executeUpdate();
        }
        return id;
    }

    public Map<String, Object> getOperation(String project, String operation) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT operation_id, project_id, instance_id, operation_type, status, target_link, error_json, created_at " +
                             "FROM cloudsql_operations WHERE project_id = ? AND operation_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, operation);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? operationMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listOperations(String project) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT operation_id, project_id, instance_id, operation_type, status, target_link, error_json, created_at " +
                             "FROM cloudsql_operations WHERE project_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, project);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(operationMap(rs));
            }
        }
        return result;
    }

    public void clearAll() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM cloudsql_operations");
            stmt.execute("DELETE FROM cloudsql_users");
            stmt.execute("DELETE FROM cloudsql_databases");
            stmt.execute("DELETE FROM cloudsql_instances");
        } catch (SQLException ignored) {
        }
    }

    private Map<String, Object> instanceMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("instance_id", rs.getString("instance_id"));
        row.put("region", rs.getString("region"));
        row.put("database_version", rs.getString("database_version"));
        row.put("tier", rs.getString("tier"));
        row.put("state", rs.getString("state"));
        row.put("backend_type", rs.getString("backend_type"));
        row.put("connection_name", rs.getString("connection_name"));
        row.put("settings_json", rs.getString("settings_json"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    private Map<String, Object> databaseMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("instance_id", rs.getString("instance_id"));
        row.put("database_name", rs.getString("database_name"));
        row.put("charset", rs.getString("charset"));
        row.put("collation", rs.getString("collation")); // column aliased in query as "collation"
        row.put("physical_name", rs.getString("physical_name"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    private Map<String, Object> userMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("instance_id", rs.getString("instance_id"));
        row.put("user_name", rs.getString("user_name"));
        row.put("host", rs.getString("host"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    private Map<String, Object> operationMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("operation_id", rs.getString("operation_id"));
        row.put("project_id", rs.getString("project_id"));
        row.put("instance_id", rs.getString("instance_id"));
        row.put("operation_type", rs.getString("operation_type"));
        row.put("status", rs.getString("status"));
        row.put("target_link", rs.getString("target_link"));
        row.put("error_json", rs.getString("error_json"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    private String physicalName(String project, String instance, String database) {
        return ("lc_" + project + "_" + instance + "_" + database)
                .toLowerCase()
                .replaceAll("[^a-z0-9_]", "_");
    }

    private String getPhysicalName(String project, String instance, String database) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT physical_name FROM cloudsql_databases WHERE project_id = ? AND instance_id = ? AND database_name = ?")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            ps.setString(3, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("physical_name") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private String getDatabaseVersion(String project, String instance) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT database_version FROM cloudsql_instances WHERE project_id = ? AND instance_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, instance);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("database_version") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Detect duplicate key / unique constraint violations across H2 and PostgreSQL.
     */
    private static boolean isDuplicateKeyError(SQLException e) {
        if (e == null) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        // PostgreSQL: "duplicate key value violates unique constraint"
        // H2: "Unique index or primary key violation"
        return msg.contains("duplicate key") || msg.contains("Unique index or primary key violation")
                || msg.contains("violates unique constraint");
    }
}
