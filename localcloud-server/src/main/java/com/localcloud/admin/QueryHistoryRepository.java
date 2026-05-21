package com.localcloud.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryHistoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(QueryHistoryRepository.class);

    private final PostgresDataSource dataSource;

    public QueryHistoryRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void record(String projectId, String service, String sql, String instance,
                       String database, long durationMs, int rowCount,
                       boolean success, String errorMessage) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO query_history (project_id, service, sql, instance, database_name, " +
                 "duration_ms, row_count, success, error_message) " +
                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, projectId);
            ps.setString(2, service);
            ps.setString(3, sql);
            if (instance != null) ps.setString(4, instance); else ps.setNull(4, java.sql.Types.VARCHAR);
            if (database != null) ps.setString(5, database); else ps.setNull(5, java.sql.Types.VARCHAR);
            ps.setLong(6, durationMs);
            ps.setInt(7, rowCount);
            ps.setBoolean(8, success);
            if (errorMessage != null) ps.setString(9, errorMessage); else ps.setNull(9, java.sql.Types.VARCHAR);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to record query history: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> list(String projectId, String service, int limit, int offset) {
        List<Map<String, Object>> entries = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT id, project_id, service, sql, instance, database_name, " +
            "duration_ms, row_count, success, error_message, executed_at " +
            "FROM query_history WHERE project_id = ?");
        if (service != null && !service.isBlank()) {
            sql.append(" AND service = ?");
        }
        sql.append(" ORDER BY executed_at DESC LIMIT ? OFFSET ?");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int paramIdx = 1;
            ps.setString(paramIdx++, projectId);
            if (service != null && !service.isBlank()) {
                ps.setString(paramIdx++, service);
            }
            ps.setInt(paramIdx++, limit);
            ps.setInt(paramIdx, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", rs.getLong("id"));
                    entry.put("project_id", rs.getString("project_id"));
                    entry.put("service", rs.getString("service"));
                    entry.put("sql", rs.getString("sql"));
                    entry.put("instance", rs.getString("instance"));
                    entry.put("database", rs.getString("database_name"));
                    entry.put("duration_ms", rs.getLong("duration_ms"));
                    entry.put("row_count", rs.getInt("row_count"));
                    entry.put("success", rs.getBoolean("success"));
                    entry.put("error_message", rs.getString("error_message"));
                    entry.put("executed_at", rs.getTimestamp("executed_at") != null
                            ? rs.getTimestamp("executed_at").toString() : null);
                    entries.add(entry);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to list query history: {}", e.getMessage());
        }
        return entries;
    }

    public int count(String projectId, String service) {
        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) FROM query_history WHERE project_id = ?");
        if (service != null && !service.isBlank()) {
            sql.append(" AND service = ?");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int paramIdx = 1;
            ps.setString(paramIdx++, projectId);
            if (service != null && !service.isBlank()) {
                ps.setString(paramIdx++, service);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to count query history: {}", e.getMessage());
        }
        return 0;
    }

}
