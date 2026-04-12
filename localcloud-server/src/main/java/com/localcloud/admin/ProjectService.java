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
     * @return list of project maps with project_id, display_name, and created_at
     */
    public List<Map<String, Object>> listProjects() throws SQLException {
        List<Map<String, Object>> projects = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT project_id, display_name, created_at FROM projects ORDER BY created_at")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> project = new LinkedHashMap<>();
                project.put("project_id", rs.getString("project_id"));
                project.put("display_name", rs.getString("display_name"));
                project.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                projects.add(project);
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO projects (project_id, display_name) VALUES (?, ?) " +
                 "ON CONFLICT (project_id) DO NOTHING")) {
            stmt.setString(1, projectId);
            stmt.setString(2, displayName);
            stmt.executeUpdate();
        }

        // Return the created/existing project
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT project_id, display_name, created_at FROM projects WHERE project_id = ?")) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Map<String, Object> project = new LinkedHashMap<>();
                project.put("project_id", rs.getString("project_id"));
                project.put("display_name", rs.getString("display_name"));
                project.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                return project;
            }
        }

        // Should not happen, but return minimal map if it does
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("project_id", projectId);
        project.put("display_name", displayName);
        return project;
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
                    "gke_clusters", "redis_data", "bigtable_data", "service_routing"
                };

                for (String table : tables) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE project_id = ?")) {
                        stmt.setString(1, projectId);
                        stmt.executeUpdate();
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
