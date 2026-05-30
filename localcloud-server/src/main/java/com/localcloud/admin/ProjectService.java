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

/**
 * Service for managing projects in the LocalCloud server.
 * Provides CRUD operations for the projects table and cascading
 * deletes to all service data tables when a project is removed.
 */
public class ProjectService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectService.class);

    private final PostgresDataSource dataSource;

    public ProjectService(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * List all projects.
     *
     * @return list of project maps with project_id, display_name, labels, state, and created_at
     */
    public List<Map<String, Object>> listProjects() throws SQLException {
        List<Map<String, Object>> projects = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT project_id, display_name, labels, state, created_at FROM projects ORDER BY created_at")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                projects.add(projectFromRs(rs));
            }
        }
        return projects;
    }

    /**
     * Create a new project.
     *
     * @param projectId   the project identifier
     * @param displayName the human-readable display name
     * @return the created project as a map
     */
    public Map<String, Object> createProject(String projectId, String displayName) throws SQLException {
        return createProject(projectId, displayName, null);
    }

    /**
     * Create a new project with optional labels.
     *
     * @param projectId   the project identifier
     * @param displayName the human-readable display name
     * @param labels      labels as JSON string
     * @return the created project as a map
     */
    public Map<String, Object> createProject(String projectId, String displayName, String labels) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO projects (project_id, display_name, labels) VALUES (?, ?, ?) " +
                 "ON CONFLICT (project_id) DO NOTHING")) {
            stmt.setString(1, projectId);
            stmt.setString(2, displayName != null ? displayName : projectId);
            stmt.setString(3, labels != null ? labels : "{}");
            stmt.executeUpdate();
        } catch (SQLException e) {
            // H2 does not support ON CONFLICT — fall back to check-then-insert
            if (e.getMessage() != null && e.getMessage().contains("ON CONFLICT")) {
                Map<String, Object> existing = getProject(projectId);
                if (existing != null) {
                    return existing;
                }
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO projects (project_id, display_name, labels) VALUES (?, ?, ?)")) {
                    stmt.setString(1, projectId);
                    stmt.setString(2, displayName != null ? displayName : projectId);
                    stmt.setString(3, labels != null ? labels : "{}");
                    stmt.executeUpdate();
                }
            } else {
                throw e;
            }
        }

        // Return the created/existing project
        return getProject(projectId);
    }

    /**
     * Delete a project and cascade-delete all associated data from service tables.
     * The default project cannot be deleted.
     *
     * @param projectId        the project to delete
     * @param defaultProjectId the default project ID that cannot be deleted
     * @throws IllegalArgumentException if attempting to delete the default project
     */
    public void deleteProject(String projectId, String defaultProjectId) throws SQLException {
        if (projectId.equals(defaultProjectId)) {
            throw new IllegalArgumentException("Cannot delete the default project: " + defaultProjectId);
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Cascade delete from all service data tables
                String[] tables = {
                    "secrets", "secret_versions", "task_queues", "cloud_tasks",
                    "log_entries", "time_series", "metric_points",
                    "compute_instances", "cloudrun_services", "cloudrun_revisions",
                    "gke_clusters", "bigtable_data", "service_routing",
                    "usage_metrics", "gcs_bucket_projects",
                    "workflow_step_entries", "workflow_executions", "workflows",
                    "workflow_env_vars", "workflow_config"
                };

                for (String table : tables) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE project_id = ?")) {
                        stmt.setString(1, projectId);
                        stmt.executeUpdate();
                    } catch (SQLException e) {
                        // Table may not exist in test environments — safe to skip
                        logger.debug("Skipping cascade delete on table '{}': {}", table, e.getMessage());
                    }
                }

                // Delete the project itself
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM projects WHERE project_id = ?")) {
                    stmt.setString(1, projectId);
                    stmt.executeUpdate();
                }

                conn.commit();
                logger.info("Deleted project '{}' and all associated data", projectId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Get a single project by ID.
     *
     * @param projectId the project identifier
     * @return the project as a map, or null if not found
     */
    public Map<String, Object> getProject(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT project_id, display_name, labels, state, created_at FROM projects WHERE project_id = ?")) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return projectFromRs(rs);
            }
        }
        return null;
    }

    private Map<String, Object> projectFromRs(ResultSet rs) throws SQLException {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("project_id", rs.getString("project_id"));
        project.put("display_name", rs.getString("display_name"));
        String labels = rs.getString("labels");
        project.put("labels", labels != null ? labels : "{}");
        project.put("state", rs.getString("state"));
        project.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
        return project;
    }

    /**
     * Update an existing project's display name and labels.
     *
     * @param projectId   the project identifier
     * @param displayName the new display name (nullable)
     * @param labels      labels as a JSON string (nullable)
     * @return the updated project as a map
     */
    public Map<String, Object> updateProject(String projectId, String displayName, String labels) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Build dynamic update query for non-null fields
            boolean hasName = displayName != null && !displayName.isBlank();
            boolean hasLabels = labels != null;

            if (!hasName && !hasLabels) {
                return getProject(projectId);
            }

            StringBuilder sql = new StringBuilder("UPDATE projects SET ");
            List<String> sets = new ArrayList<>();
            if (hasName) sets.add("display_name = ?");
            if (hasLabels) sets.add("labels = ?");
            sql.append(String.join(", ", sets));
            sql.append(" WHERE project_id = ?");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                if (hasName) stmt.setString(idx++, displayName);
                if (hasLabels) stmt.setString(idx++, labels);
                stmt.setString(idx, projectId);
                stmt.executeUpdate();
            }
        }
        return getProject(projectId);
    }

    /**
     * Check whether a project exists.
     *
     * @param projectId the project identifier to check
     * @return true if the project exists
     */
    public boolean projectExists(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT 1 FROM projects WHERE project_id = ?")) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        }
    }
}
