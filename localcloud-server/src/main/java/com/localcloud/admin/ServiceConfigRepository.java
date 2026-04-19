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
 * Repository for persisted service enable/disable configuration.
 * Stores toggle state in the service_config table so it survives container restarts.
 */
public class ServiceConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(ServiceConfigRepository.class);

    private final PostgresDataSource dataSource;

    public ServiceConfigRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Get all persisted service configurations.
     *
     * @return map of serviceId → enabled
     */
    public Map<String, Boolean> findAll() throws SQLException {
        Map<String, Boolean> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT service_id, enabled FROM service_config ORDER BY service_id")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.put(rs.getString("service_id"), rs.getBoolean("enabled"));
            }
        }
        return result;
    }

    /**
     * Get persisted config for a specific service.
     * Returns null if no persisted config exists.
     */
    public Boolean findByServiceId(String serviceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT enabled FROM service_config WHERE service_id = ?")) {
            stmt.setString(1, serviceId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getBoolean("enabled");
            }
        }
        return null;
    }

    /**
     * Insert or update persisted config for a service.
     */
    public void upsert(String serviceId, boolean enabled) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO service_config (service_id, enabled, updated_at) " +
                 "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                 "ON CONFLICT (service_id) DO UPDATE SET enabled = EXCLUDED.enabled, " +
                 "updated_at = CURRENT_TIMESTAMP")) {
            stmt.setString(1, serviceId);
            stmt.setBoolean(2, enabled);
            stmt.executeUpdate();
        }
        logger.info("Service config persisted: {} → enabled={}", serviceId, enabled);
    }

    /**
     * Bulk upsert multiple service configs.
     */
    public void upsertAll(Map<String, Boolean> configs) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO service_config (service_id, enabled, updated_at) " +
                 "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                 "ON CONFLICT (service_id) DO UPDATE SET enabled = EXCLUDED.enabled, " +
                 "updated_at = CURRENT_TIMESTAMP")) {
            for (Map.Entry<String, Boolean> entry : configs.entrySet()) {
                stmt.setString(1, entry.getKey());
                stmt.setBoolean(2, entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        logger.info("Service config batch persisted: {} services", configs.size());
    }
}
