package com.localcloud.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository for sync credential storage (OAuth tokens, service account keys).
 * Security-critical: {@link #getStatus} must never expose credential_data.
 */
public class SyncCredentialRepository {

    private static final Logger logger = LoggerFactory.getLogger(SyncCredentialRepository.class);

    private final DataSource dataSource;

    public SyncCredentialRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Save (upsert) a credential for a project. Deletes any existing credential
     * for the project, then inserts the new one within a transaction.
     *
     * @param projectId      the local project identifier
     * @param sourceProject  the real GCP project to connect to
     * @param authMethod     "oauth" or "service_account"
     * @param credentialData raw credential JSON (token or key)
     */
    public void save(String projectId, String sourceProject, String authMethod,
                     String credentialData) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM sync_credentials WHERE project_id = ?")) {
                    del.setString(1, projectId);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO sync_credentials (project_id, source_project, auth_method, credential_data, created_at) " +
                        "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                    ins.setString(1, projectId);
                    ins.setString(2, sourceProject);
                    ins.setString(3, authMethod);
                    ins.setString(4, credentialData);
                    ins.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        logger.info("Sync credential saved: project={}, source={}, method={}",
                projectId, sourceProject, authMethod);
    }

    /**
     * Get connection status for a project. Returns source_project, auth_method,
     * created_at, and connected=true. NEVER returns credential_data.
     *
     * @param projectId the local project identifier
     * @return status map or null if no credential exists
     */
    public Map<String, String> getStatus(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT source_project, auth_method, created_at FROM sync_credentials WHERE project_id = ?")) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Map<String, String> status = new LinkedHashMap<>();
                status.put("source_project", rs.getString("source_project"));
                status.put("auth_method", rs.getString("auth_method"));
                status.put("created_at", rs.getString("created_at"));
                status.put("connected", "true");
                return status;
            }
        }
        return null;
    }

    /**
     * Get raw credential data for internal use (e.g., building authenticated clients).
     * This method returns sensitive data — callers must never expose it to API responses.
     *
     * @param projectId the local project identifier
     * @return raw credential JSON or null if no credential exists
     */
    public String getCredentialData(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT credential_data FROM sync_credentials WHERE project_id = ?")) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("credential_data");
            }
        }
        return null;
    }

    /**
     * Delete credential for a project.
     *
     * @param projectId the local project identifier
     */
    public void delete(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "DELETE FROM sync_credentials WHERE project_id = ?")) {
            stmt.setString(1, projectId);
            stmt.executeUpdate();
        }
        logger.info("Sync credential deleted: project={}", projectId);
    }
}
