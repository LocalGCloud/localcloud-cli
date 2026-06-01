package com.localcloud.emulators.logging;

import com.localcloud.persistence.PostgresDataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository for logging sinks persisted in PostgreSQL.
 */
public class LoggingSinkRepository {
    private static final Logger log = LoggerFactory.getLogger(LoggingSinkRepository.class);
    private final PostgresDataSource dataSource;

    public LoggingSinkRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String create(String projectId, String sinkName, String destination) {
        String sql = "INSERT INTO logging_sinks (project_id, sink_id, destination, writer_identity) " +
                     "VALUES (?, ?, ?, 'serviceAccount:cloud-logs@localcloud.iam.gserviceaccount.com') " +
                     "ON CONFLICT (project_id, sink_id) DO UPDATE SET destination = EXCLUDED.destination";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, sinkName);
            ps.setString(3, destination != null ? destination : "bigquery.googleapis.com");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to create logging sink", e);
        }
        return find(projectId, sinkName);
    }

    public String find(String projectId, String sinkId) {
        String sql = "SELECT sink_id, destination, writer_identity FROM logging_sinks WHERE project_id = ? AND sink_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, sinkId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return buildSinkJson(projectId, rs.getString("sink_id"),
                        rs.getString("destination"), rs.getString("writer_identity"));
            }
        } catch (SQLException e) {
            log.error("Failed to find logging sink", e);
        }
        return null;
    }

    public boolean delete(String projectId, String sinkId) {
        String sql = "DELETE FROM logging_sinks WHERE project_id = ? AND sink_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, sinkId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete logging sink", e);
        }
        return false;
    }

    public List<String> list(String projectId) {
        List<String> sinks = new ArrayList<>();
        String sql = "SELECT sink_id, destination, writer_identity FROM logging_sinks WHERE project_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sinks.add(buildSinkJson(projectId, rs.getString("sink_id"),
                        rs.getString("destination"), rs.getString("writer_identity")));
            }
        } catch (SQLException e) {
            log.error("Failed to list logging sinks", e);
        }
        return sinks;
    }

    static String buildSinkJson(String projectId, String sinkId, String destination, String writerIdentity) {
        return "{\"name\":\"" + sinkId + "\"," +
               "\"destination\":\"" + (destination != null ? destination : "bigquery.googleapis.com") + "\"," +
               "\"writerIdentity\":\"" + (writerIdentity != null ? writerIdentity : "serviceAccount:cloud-logs@localcloud.iam.gserviceaccount.com") + "\"}";
    }
}
