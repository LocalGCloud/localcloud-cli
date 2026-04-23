package com.localcloud.emulators.workflows;

import com.localcloud.persistence.PostgresDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL persistence layer for Cloud Workflows.
 * Manages workflows and workflow_executions tables.
 */
public class WorkflowsStore {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowsStore.class);
    private final PostgresDataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowsStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // --- Workflow CRUD ---

    public void createWorkflow(String projectId, String locationId, String workflowId,
                               String sourceContents, String labelsJson, String serviceAccount) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflows (project_id, location_id, workflow_id, source_contents, state, revision_id, labels, service_account, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setString(4, sourceContents);
            ps.setString(5, labelsJson != null ? labelsJson : "{}");
            ps.setString(6, serviceAccount);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> getWorkflow(String projectId, String locationId, String workflowId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflows WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND state != 'DELETED'")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
                return null;
            }
        }
    }

    public void updateWorkflow(String projectId, String locationId, String workflowId,
                               String sourceContents) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflows SET source_contents = ?, revision_id = revision_id + 1, updated_at = CURRENT_TIMESTAMP " +
                "WHERE project_id = ? AND location_id = ? AND workflow_id = ? AND state != 'DELETED'")) {
            ps.setString(1, sourceContents);
            ps.setString(2, projectId);
            ps.setString(3, locationId);
            ps.setString(4, workflowId);
            ps.executeUpdate();
        }
    }

    public void deleteWorkflow(String projectId, String locationId, String workflowId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflows SET state = 'DELETED', updated_at = CURRENT_TIMESTAMP " +
                "WHERE project_id = ? AND location_id = ? AND workflow_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> listWorkflows(String projectId, String locationId, int pageSize) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflows WHERE project_id = ? AND location_id = ? AND state != 'DELETED' ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setInt(3, pageSize > 0 ? pageSize : 100);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) results.add(rowToMap(rs));
                return results;
            }
        }
    }

    // --- Execution CRUD ---

    public String createExecution(String workflowId, String projectId, String locationId,
                                  String argument, String workflowRevisionId) throws SQLException {
        String executionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflow_executions (execution_id, workflow_id, project_id, location_id, state, argument, workflow_revision_id, start_time) " +
                "VALUES (?, ?, ?, ?, 'QUEUED', ?::jsonb, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, executionId);
            ps.setString(2, workflowId);
            ps.setString(3, projectId);
            ps.setString(4, locationId);
            ps.setString(5, argument != null ? argument : "null");
            ps.setString(6, workflowRevisionId);
            ps.executeUpdate();
        }
        return executionId;
    }

    public Map<String, Object> getExecution(String projectId, String locationId,
                                             String workflowId, String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflow_executions WHERE execution_id = ? AND project_id = ? AND location_id = ? AND workflow_id = ?")) {
            ps.setString(1, executionId);
            ps.setString(2, projectId);
            ps.setString(3, locationId);
            ps.setString(4, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
                return null;
            }
        }
    }

    public boolean updateExecutionState(String executionId, String state, String result, String error) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE workflow_executions SET state = ?, result = ?::jsonb, error = ?::jsonb, end_time = CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN CURRENT_TIMESTAMP ELSE end_time END " +
                "WHERE execution_id = ? AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")) {
            ps.setString(1, state);
            ps.setString(2, result);
            ps.setString(3, error);
            ps.setString(4, state);
            ps.setString(5, executionId);
            int rowsUpdated = ps.executeUpdate();
            if (rowsUpdated == 0) {
                logger.warn("State update to '{}' skipped for execution {} — already in a terminal state", state, executionId);
            }
            return rowsUpdated > 0;
        }
    }

    public List<Map<String, Object>> listExecutions(String projectId, String locationId,
                                                     String workflowId, int pageSize) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflow_executions WHERE project_id = ? AND location_id = ? AND workflow_id = ? ORDER BY start_time DESC LIMIT ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setInt(4, pageSize > 0 ? pageSize : 100);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) results.add(rowToMap(rs));
                return results;
            }
        }
    }

    public String getProjectIdForExecution(String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT project_id FROM workflow_executions WHERE execution_id = ?")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("project_id");
                return null;
            }
        }
    }

    public void resetAll() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM workflow_executions");
            st.execute("DELETE FROM workflows");
        } catch (SQLException e) {
            logger.error("Failed to reset workflows data", e);
        }
    }

    public void resetByProject(String projectId) {
        try (Connection conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("DELETE FROM workflow_executions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM workflows WHERE project_id = ?")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Failed to reset workflows for project {}", projectId, e);
        }
    }

    // --- Seed support (UPSERT) ---

    public void upsertWorkflow(String projectId, String locationId, String workflowId,
                                String sourceContents) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO workflows (project_id, location_id, workflow_id, source_contents, state, revision_id, labels, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (project_id, location_id, workflow_id) DO UPDATE SET " +
                "source_contents = EXCLUDED.source_contents, revision_id = workflows.revision_id + 1, updated_at = CURRENT_TIMESTAMP")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, workflowId);
            ps.setString(4, sourceContents);
            ps.executeUpdate();
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
