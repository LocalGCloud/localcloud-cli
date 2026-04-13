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
 * Repository for persistent cumulative usage metrics.
 * Uses UPSERT (INSERT ... ON CONFLICT UPDATE) to keep one lean row
 * per project+service combination — no row bloat.
 */
public class UsageMetricsRepository {

    private static final Logger logger = LoggerFactory.getLogger(UsageMetricsRepository.class);

    private final PostgresDataSource dataSource;

    public UsageMetricsRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Atomically increment the request count for a project+service pair.
     * Creates the row if it doesn't exist, otherwise adds delta to existing count.
     *
     * @param projectId the project identifier
     * @param serviceId the service identifier (e.g., "gcs", "pubsub")
     * @param delta     the number of requests to add
     */
    public void incrementCount(String projectId, String serviceId, long delta) {
        if (delta <= 0) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO usage_metrics (project_id, service_id, request_count, last_updated) " +
                 "VALUES (?, ?, ?, NOW()) " +
                 "ON CONFLICT (project_id, service_id) " +
                 "DO UPDATE SET request_count = usage_metrics.request_count + EXCLUDED.request_count, " +
                 "             last_updated = NOW()")) {
            ps.setString(1, projectId);
            ps.setString(2, serviceId);
            ps.setLong(3, delta);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to persist usage metric for {}/{}: {}", projectId, serviceId, e.getMessage());
        }
    }

    /**
     * Flush a batch of in-memory deltas to persistent storage in a single transaction.
     * Used by the periodic flush task to minimize DB round-trips.
     *
     * @param projectId the project identifier
     * @param deltas    map of serviceId to delta counts to flush
     */
    public void flushDeltas(String projectId, Map<String, Long> deltas) {
        if (deltas.isEmpty()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO usage_metrics (project_id, service_id, request_count, last_updated) " +
                 "VALUES (?, ?, ?, NOW()) " +
                 "ON CONFLICT (project_id, service_id) " +
                 "DO UPDATE SET request_count = usage_metrics.request_count + EXCLUDED.request_count, " +
                 "             last_updated = NOW()")) {
            conn.setAutoCommit(false);
            for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                if (entry.getValue() <= 0) continue;
                ps.setString(1, projectId);
                ps.setString(2, entry.getKey());
                ps.setLong(3, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            logger.warn("Failed to flush usage deltas for project {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * Get all cumulative counts for a project.
     *
     * @param projectId the project identifier
     * @return map of serviceId to cumulative request count
     */
    public Map<String, Long> getCountsByProject(String projectId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT service_id, request_count FROM usage_metrics WHERE project_id = ?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                counts.put(rs.getString("service_id"), rs.getLong("request_count"));
            }
        } catch (SQLException e) {
            logger.warn("Failed to read usage metrics for project {}: {}", projectId, e.getMessage());
        }
        return counts;
    }

    /**
     * Get cumulative counts across all projects (global totals).
     *
     * @return map of serviceId to total request count across all projects
     */
    public Map<String, Long> getGlobalCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT service_id, SUM(request_count) as total FROM usage_metrics GROUP BY service_id")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                counts.put(rs.getString("service_id"), rs.getLong("total"));
            }
        } catch (SQLException e) {
            logger.warn("Failed to read global usage metrics: {}", e.getMessage());
        }
        return counts;
    }
}
