package com.localcloud.emulators.secretmanager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storage layer for the Secret Manager emulator.
 * Secrets and secret versions are persisted in PostgreSQL.
 */
public class SecretManagerStore {

    private static final Logger logger = LoggerFactory.getLogger(SecretManagerStore.class);

    private final PostgresDataSource dataSource;

    public SecretManagerStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // --- Secret operations ---

    public void createSecret(String projectId, String secretId, String labelsJson) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO secrets (project_id, secret_id, labels, created_at) " +
                     "VALUES (?, ?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            ps.setString(3, labelsJson != null ? labelsJson : "{}");
            ps.executeUpdate();
            logger.debug("Created secret: projects/{}/secrets/{}", projectId, secretId);
        }
    }

    public void updateSecret(String projectId, String secretId, String labelsJson) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE secrets SET labels = ? WHERE project_id = ? AND secret_id = ?")) {
            ps.setString(1, labelsJson != null ? labelsJson : "{}");
            ps.setString(2, projectId);
            ps.setString(3, secretId);
            ps.executeUpdate();
            logger.debug("Updated secret: projects/{}/secrets/{}", projectId, secretId);
        }
    }

    public Map<String, Object> getSecret(String projectId, String secretId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, secret_id, labels, created_at " +
                     "FROM secrets WHERE project_id = ? AND secret_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("project_id", rs.getString("project_id"));
                    result.put("secret_id", rs.getString("secret_id"));
                    result.put("labels", rs.getString("labels"));
                    result.put("created_at", rs.getTimestamp("created_at"));
                    return result;
                }
                return null;
            }
        }
    }

    public List<Map<String, Object>> listSecrets(String projectId) throws SQLException {
        return listSecrets(projectId, 0, 0);
    }

    public List<Map<String, Object>> listSecrets(String projectId, int limit, int offset) throws SQLException {
        List<Map<String, Object>> secrets = new ArrayList<>();
        String sql = "SELECT project_id, secret_id, labels, created_at " +
                     "FROM secrets WHERE project_id = ? ORDER BY secret_id";
        if (limit > 0) {
            sql += " LIMIT " + limit + " OFFSET " + offset;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("project_id", rs.getString("project_id"));
                    result.put("secret_id", rs.getString("secret_id"));
                    result.put("labels", rs.getString("labels"));
                    result.put("created_at", rs.getTimestamp("created_at"));
                    secrets.add(result);
                }
            }
        }
        return secrets;
    }

    public boolean deleteSecret(String projectId, String secretId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Delete versions first (cascade)
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM secret_versions WHERE project_id = ? AND secret_id = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, secretId);
                ps.executeUpdate();
            }
            // Delete the secret
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM secrets WHERE project_id = ? AND secret_id = ?")) {
                ps.setString(1, projectId);
                ps.setString(2, secretId);
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    logger.debug("Deleted secret: projects/{}/secrets/{}", projectId, secretId);
                    return true;
                }
                return false;
            }
        }
    }

    // --- Secret Version operations ---

    public Map<String, Object> addSecretVersion(String projectId, String secretId, byte[] data) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Atomic insert with subquery to compute next version number, avoiding race conditions
            int versionNumber;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO secret_versions (project_id, secret_id, version_number, payload, state, created_at) " +
                    "VALUES (?, ?, COALESCE((SELECT MAX(version_number) FROM secret_versions WHERE project_id = ? AND secret_id = ?), 0) + 1, ?, 'ENABLED', CURRENT_TIMESTAMP) " +
                    "RETURNING version_number")) {
                ps.setString(1, projectId);
                ps.setString(2, secretId);
                ps.setString(3, projectId);
                ps.setString(4, secretId);
                ps.setBytes(5, data);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    versionNumber = rs.getInt(1);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("project_id", projectId);
            result.put("secret_id", secretId);
            result.put("version_number", versionNumber);
            result.put("state", "ENABLED");
            logger.debug("Added secret version: projects/{}/secrets/{}/versions/{}", projectId, secretId, versionNumber);
            return result;
        }
    }

    public Map<String, Object> getSecretVersion(String projectId, String secretId, String versionId) throws SQLException {
        String sql;
        if ("latest".equals(versionId)) {
            sql = "SELECT project_id, secret_id, version_number, state, created_at " +
                  "FROM secret_versions WHERE project_id = ? AND secret_id = ? " +
                  "AND state != 'DESTROYED' ORDER BY version_number DESC LIMIT 1";
        } else {
            sql = "SELECT project_id, secret_id, version_number, state, created_at " +
                  "FROM secret_versions WHERE project_id = ? AND secret_id = ? AND version_number = ?";
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            if (!"latest".equals(versionId)) {
                ps.setInt(3, Integer.parseInt(versionId));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("project_id", rs.getString("project_id"));
                    result.put("secret_id", rs.getString("secret_id"));
                    result.put("version_number", rs.getInt("version_number"));
                    result.put("state", rs.getString("state"));
                    result.put("created_at", rs.getTimestamp("created_at"));
                    return result;
                }
                return null;
            }
        }
    }

    public List<Map<String, Object>> listSecretVersions(String projectId, String secretId) throws SQLException {
        return listSecretVersions(projectId, secretId, 0, 0);
    }

    public List<Map<String, Object>> listSecretVersions(String projectId, String secretId, int limit, int offset) throws SQLException {
        List<Map<String, Object>> versions = new ArrayList<>();
        String sql = "SELECT project_id, secret_id, version_number, state, created_at " +
                     "FROM secret_versions WHERE project_id = ? AND secret_id = ? " +
                     "ORDER BY version_number ASC";
        if (limit > 0) {
            sql += " LIMIT " + limit + " OFFSET " + offset;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("project_id", rs.getString("project_id"));
                    result.put("secret_id", rs.getString("secret_id"));
                    result.put("version_number", rs.getInt("version_number"));
                    result.put("state", rs.getString("state"));
                    result.put("created_at", rs.getTimestamp("created_at"));
                    versions.add(result);
                }
            }
        }
        return versions;
    }

    public byte[] accessSecretVersion(String projectId, String secretId, String versionId) throws SQLException {
        String sql;
        if ("latest".equals(versionId)) {
            sql = "SELECT payload, state FROM secret_versions " +
                  "WHERE project_id = ? AND secret_id = ? AND state = 'ENABLED' " +
                  "ORDER BY version_number DESC LIMIT 1";
        } else {
            sql = "SELECT payload, state FROM secret_versions " +
                  "WHERE project_id = ? AND secret_id = ? AND version_number = ?";
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            if (!"latest".equals(versionId)) {
                ps.setInt(3, Integer.parseInt(versionId));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String state = rs.getString("state");
                    if (!"ENABLED".equals(state)) {
                        return null; // Not accessible if not enabled
                    }
                    return rs.getBytes("payload");
                }
                return null;
            }
        }
    }

    public boolean disableSecretVersion(String projectId, String secretId, int versionNumber) throws SQLException {
        return updateVersionState(projectId, secretId, versionNumber, "DISABLED", "ENABLED");
    }

    public boolean enableSecretVersion(String projectId, String secretId, int versionNumber) throws SQLException {
        return updateVersionState(projectId, secretId, versionNumber, "ENABLED", "DISABLED");
    }

    public boolean destroySecretVersion(String projectId, String secretId, int versionNumber) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE secret_versions SET state = 'DESTROYED', payload = NULL " +
                     "WHERE project_id = ? AND secret_id = ? AND version_number = ? AND state != 'DESTROYED'")) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            ps.setInt(3, versionNumber);
            return ps.executeUpdate() > 0;
        }
    }

    private boolean updateVersionState(String projectId, String secretId, int versionNumber,
                                        String newState, String requiredCurrentState) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE secret_versions SET state = ? " +
                     "WHERE project_id = ? AND secret_id = ? AND version_number = ? AND state = ?")) {
            ps.setString(1, newState);
            ps.setString(2, projectId);
            ps.setString(3, secretId);
            ps.setInt(4, versionNumber);
            ps.setString(5, requiredCurrentState);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean secretExists(String projectId, String secretId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM secrets WHERE project_id = ? AND secret_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void clearAll() {
        try (Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM secret_versions");
            stmt.execute("DELETE FROM secrets");
            logger.info("Cleared all Secret Manager data");
        } catch (SQLException e) {
            logger.error("Failed to clear Secret Manager data: {}", e.getMessage(), e);
        }
    }

    public int getLatestVersionNumber(String projectId, String secretId) {
        String sql = "SELECT MAX(version_number) FROM secret_versions WHERE project_id = ? AND secret_id = ? AND state != 'DESTROYED'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, secretId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                throw new RuntimeException("No versions found");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve latest version", e);
        }
    }

    // ---- Helpers ----

    /**
     * Parse "projects/{project}/secrets/{secret}" into [project, secret].
     */
    static String[] parseSecretName(String fullName) {
        String[] segments = fullName.split("/");
        if (segments.length != 4 || !"projects".equals(segments[0]) || !"secrets".equals(segments[2])) {
            throw new IllegalArgumentException("Invalid secret name: " + fullName);
        }
        return new String[]{segments[1], segments[3]};
    }

    /**
     * Parse "projects/{project}/secrets/{secret}/versions/{version}" into [project, secret, version].
     */
    static String[] parseVersionName(String fullName) {
        String[] segments = fullName.split("/");
        if (segments.length != 6 || !"projects".equals(segments[0]) ||
            !"secrets".equals(segments[2]) || !"versions".equals(segments[4])) {
            throw new IllegalArgumentException("Invalid secret version name: " + fullName);
        }
        return new String[]{segments[1], segments[3], segments[5]};
    }

    /**
     * Extract project ID from "projects/{project}" format.
     */
    static String extractProject(String fullName) {
        String[] segments = fullName.split("/");
        if (segments.length >= 2 && "projects".equals(segments[0])) {
            return segments[1];
        }
        throw new IllegalArgumentException("Cannot extract project from: " + fullName);
    }
}
