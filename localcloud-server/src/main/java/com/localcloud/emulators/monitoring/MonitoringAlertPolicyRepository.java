package com.localcloud.emulators.monitoring;

import com.localcloud.persistence.PostgresDataSource;
import java.sql.*;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository for monitoring alert policies persisted in the shared
 * {@code alert_policies} PostgreSQL table.
 */
public class MonitoringAlertPolicyRepository {
    private static final Logger log = LoggerFactory.getLogger(MonitoringAlertPolicyRepository.class);
    private final PostgresDataSource dataSource;

    public MonitoringAlertPolicyRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String create(String projectId, String displayName, String conditionsJson, String combiner) {
        String policyId = UUID.randomUUID().toString().substring(0, 8);
        String name = "projects/" + projectId + "/alertPolicies/" + policyId;
        String sql = "INSERT INTO alert_policies (project_id, name, policy_id, display_name, conditions_json, combiner) " +
                     "VALUES (?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (project_id, policy_id) DO UPDATE SET display_name = EXCLUDED.display_name, conditions_json = EXCLUDED.conditions_json, combiner = EXCLUDED.combiner";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, name);
            ps.setString(3, policyId);
            ps.setString(4, displayName != null ? displayName : "localcloud-alert");
            ps.setString(5, conditionsJson != null ? conditionsJson : "[]");
            ps.setString(6, combiner != null ? combiner : "OR");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to create monitoring alert policy", e);
        }
        return find(projectId, policyId);
    }

    public String find(String projectId, String policyId) {
        String sql = "SELECT display_name, conditions_json, combiner, enabled FROM alert_policies WHERE project_id = ? AND policy_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, policyId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return buildPolicyJson(projectId, policyId, rs.getString("display_name"),
                        rs.getString("conditions_json"), rs.getString("combiner"), rs.getBoolean("enabled"));
            }
        } catch (SQLException e) {
            log.error("Failed to find monitoring alert policy", e);
        }
        return null;
    }

    public boolean delete(String projectId, String policyId) {
        String sql = "DELETE FROM alert_policies WHERE project_id = ? AND policy_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, policyId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete monitoring alert policy", e);
        }
        return false;
    }

    public String update(String projectId, String policyId, String displayName, String conditionsJson, String combiner) {
        String sql = "UPDATE alert_policies SET display_name = ?, conditions_json = ?, combiner = ? WHERE project_id = ? AND policy_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, displayName != null ? displayName : "localcloud-alert");
            ps.setString(2, conditionsJson != null ? conditionsJson : "[]");
            ps.setString(3, combiner != null ? combiner : "OR");
            ps.setString(4, projectId);
            ps.setString(5, policyId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                return find(projectId, policyId);
            }
        } catch (SQLException e) {
            log.error("Failed to update monitoring alert policy", e);
        }
        return null;
    }

    static String buildPolicyJson(String projectId, String policyId, String displayName, String conditionsJson, String combiner, boolean enabled) {
        return "{\"name\":\"projects/" + projectId + "/alertPolicies/" + policyId + "\"," +
               "\"displayName\":\"" + (displayName != null ? displayName : "localcloud-alert") + "\"," +
               "\"combiner\":\"" + (combiner != null ? combiner : "OR") + "\"," +
               "\"conditions\":" + (conditionsJson != null ? conditionsJson : "[]") + "," +
               "\"enabled\":" + enabled + "}";
    }
}
