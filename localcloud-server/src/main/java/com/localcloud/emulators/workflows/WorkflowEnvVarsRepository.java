package com.localcloud.emulators.workflows;

import com.localcloud.persistence.PostgresDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL persistence layer for workflow environment variables.
 * Supports CRUD per project and preset, plus active preset tracking.
 */
public class WorkflowEnvVarsRepository {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowEnvVarsRepository.class);
    private final PostgresDataSource dataSource;

    public WorkflowEnvVarsRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // --- Env Var CRUD ---

    public List<Map<String, Object>> listEnvVars(String projectId, String preset) throws SQLException {
        String sql = preset != null
            ? "SELECT * FROM workflow_env_vars WHERE project_id = ? AND preset = ? ORDER BY var_name"
            : "SELECT * FROM workflow_env_vars WHERE project_id = ? ORDER BY var_name, preset";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            if (preset != null) ps.setString(2, preset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) results.add(rowToMap(rs));
                return results;
            }
        }
    }

    public Map<String, Object> createEnvVar(String projectId, String varName, String varValue, String preset) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflow_env_vars (project_id, var_name, var_value, preset, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.setString(1, projectId);
            ps.setString(2, varName);
            ps.setString(3, varValue);
            ps.setString(4, preset);
            ps.executeUpdate();
        }
        return Map.of("varName", varName, "varValue", varValue != null ? varValue : "", "preset", preset);
    }

    public boolean updateEnvVar(String projectId, String varName, String varValue, String preset) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflow_env_vars SET var_value = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE project_id = ? AND var_name = ? AND preset = ?")) {
            ps.setString(1, varValue);
            ps.setString(2, projectId);
            ps.setString(3, varName);
            ps.setString(4, preset);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteEnvVar(String projectId, String varName, String preset) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM workflow_env_vars WHERE project_id = ? AND var_name = ? AND preset = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, varName);
            ps.setString(3, preset);
            return ps.executeUpdate() > 0;
        }
    }

    public void upsertEnvVar(String projectId, String varName, String varValue, String preset) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflow_env_vars (project_id, var_name, var_value, preset, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (project_id, var_name, preset) DO UPDATE SET " +
                "var_value = EXCLUDED.var_value, updated_at = CURRENT_TIMESTAMP")) {
            ps.setString(1, projectId);
            ps.setString(2, varName);
            ps.setString(3, varValue);
            ps.setString(4, preset);
            ps.executeUpdate();
        }
    }

    public int bulkUpsert(String projectId, List<Map<String, String>> vars) throws SQLException {
        int count = 0;
        for (Map<String, String> v : vars) {
            upsertEnvVar(projectId, v.get("varName"), v.get("varValue"), v.get("preset"));
            count++;
        }
        return count;
    }

    // --- Env vars for execution context ---

    public Map<String, String> getEnvVarsForPreset(String projectId, String preset) throws SQLException {
        Map<String, String> vars = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT var_name, var_value FROM workflow_env_vars WHERE project_id = ? AND preset = ? ORDER BY var_name")) {
            ps.setString(1, projectId);
            ps.setString(2, preset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    vars.put(rs.getString("var_name"), rs.getString("var_value"));
                }
            }
        }
        return vars;
    }

    // --- Presets ---

    public List<Map<String, Object>> listPresets(String projectId) throws SQLException {
        String activePreset = getActivePreset(projectId);
        Set<String> presetNames = new LinkedHashSet<>(List.of("local", "remote", "production"));

        // Add any presets that have variables
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT preset FROM workflow_env_vars WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) presetNames.add(rs.getString("preset"));
            }
        }

        // Add custom presets from config
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT config_value FROM workflow_config WHERE project_id = ? AND config_key LIKE 'preset_%'")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) presetNames.add(rs.getString("config_value"));
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : presetNames) {
            int varCount = countVarsForPreset(projectId, name);
            Map<String, Object> preset = new LinkedHashMap<>();
            preset.put("name", name);
            preset.put("varCount", varCount);
            preset.put("isActive", name.equals(activePreset));
            result.add(preset);
        }
        return result;
    }

    private int countVarsForPreset(String projectId, String preset) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM workflow_env_vars WHERE project_id = ? AND preset = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, preset);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public String getActivePreset(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT config_value FROM workflow_config WHERE project_id = ? AND config_key = 'active_preset'")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("config_value");
                return "local"; // default
            }
        }
    }

    public void setActivePreset(String projectId, String preset) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflow_config (project_id, config_key, config_value, updated_at) " +
                "VALUES (?, 'active_preset', ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (project_id, config_key) DO UPDATE SET " +
                "config_value = EXCLUDED.config_value, updated_at = CURRENT_TIMESTAMP")) {
            ps.setString(1, projectId);
            ps.setString(2, preset);
            ps.executeUpdate();
        }
    }

    // --- Config (remote source connection) ---

    public void setConfig(String projectId, String key, String value) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflow_config (project_id, config_key, config_value, updated_at) " +
                "VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (project_id, config_key) DO UPDATE SET " +
                "config_value = EXCLUDED.config_value, updated_at = CURRENT_TIMESTAMP")) {
            ps.setString(1, projectId);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
        }
    }

    public String getConfig(String projectId, String key) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT config_value FROM workflow_config WHERE project_id = ? AND config_key = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("config_value");
                return null;
            }
        }
    }

    public void resetAll() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM workflow_env_vars");
            st.execute("DELETE FROM workflow_config");
        } catch (SQLException e) {
            logger.error("Failed to reset workflow env vars data", e);
        }
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            map.put(meta.getColumnName(i), rs.getObject(i));
        }
        return map;
    }
}
