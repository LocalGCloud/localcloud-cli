package com.localcloud.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository for per-service routing configuration (local/remote mode).
 * Persists routing state in the service_routing table so it survives container restarts.
 */
public class ServiceRoutingRepository {

    private static final Logger logger = LoggerFactory.getLogger(ServiceRoutingRepository.class);

    private final PostgresDataSource dataSource;

    public ServiceRoutingRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Get all routing configurations for a project.
     *
     * @return map of serviceId → { mode, remote_project, remote_region }
     */
    public Map<String, Map<String, String>> getAll(String projectId) throws SQLException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT service_id, mode, remote_project, remote_region FROM service_routing WHERE project_id = ?")) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, String> config = new LinkedHashMap<>();
                config.put("mode", rs.getString("mode"));
                config.put("remote_project", rs.getString("remote_project"));
                config.put("remote_region", rs.getString("remote_region"));
                result.put(rs.getString("service_id"), config);
            }
        }
        return result;
    }

    /**
     * Get routing configuration for a specific service.
     * Returns null if no configuration exists (defaults to local).
     */
    public Map<String, String> get(String projectId, String serviceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT mode, remote_project, remote_region FROM service_routing WHERE project_id = ? AND service_id = ?")) {
            stmt.setString(1, projectId);
            stmt.setString(2, serviceId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Map<String, String> config = new LinkedHashMap<>();
                config.put("mode", rs.getString("mode"));
                config.put("remote_project", rs.getString("remote_project"));
                config.put("remote_region", rs.getString("remote_region"));
                return config;
            }
        }
        return null;
    }

    /**
     * Insert or update routing configuration for a service.
     */
    public void upsert(String projectId, String serviceId, String mode,
                       String remoteProject, String remoteRegion) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM service_routing WHERE project_id = ? AND service_id = ?")) {
                    del.setString(1, projectId);
                    del.setString(2, serviceId);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO service_routing (project_id, service_id, mode, remote_project, remote_region, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                    ins.setString(1, projectId);
                    ins.setString(2, serviceId);
                    ins.setString(3, mode);
                    ins.setString(4, remoteProject);
                    ins.setString(5, remoteRegion);
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
        logger.info("Service routing updated: {}/{} → mode={}, project={}, region={}",
                projectId, serviceId, mode, remoteProject, remoteRegion);
    }
}
