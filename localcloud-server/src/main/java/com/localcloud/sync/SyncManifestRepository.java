package com.localcloud.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository for sync manifest CRUD operations.
 * Persists metadata about what data was synced from production —
 * resource paths, filters, row counts, byte totals, and status.
 */
public class SyncManifestRepository {

    private static final Logger logger = LoggerFactory.getLogger(SyncManifestRepository.class);

    private final DataSource dataSource;

    public SyncManifestRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Save (upsert) a sync manifest. Deletes any existing manifest for the same
     * project+service+resource combination, then inserts the new one within a transaction.
     *
     * @param manifest the sync manifest to save
     * @return the generated id of the inserted row
     */
    public int save(SyncManifest manifest) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM sync_manifests WHERE project_id = ? AND service_id = ? AND resource_path = ?")) {
                    del.setString(1, manifest.projectId());
                    del.setString(2, manifest.serviceId());
                    del.setString(3, manifest.resourcePath());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO sync_manifests (project_id, service_id, resource_path, source_project, " +
                        "filters_json, row_count, bytes_synced, estimated_cost, status, error_message, synced_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ins.setString(1, manifest.projectId());
                    ins.setString(2, manifest.serviceId());
                    ins.setString(3, manifest.resourcePath());
                    ins.setString(4, manifest.sourceProject());
                    ins.setString(5, manifest.filtersJson());
                    ins.setLong(6, manifest.rowCount());
                    ins.setLong(7, manifest.bytesSynced());
                    ins.setDouble(8, manifest.estimatedCost());
                    ins.setString(9, manifest.status());
                    ins.setString(10, manifest.errorMessage());
                    ins.executeUpdate();

                    ResultSet keys = ins.getGeneratedKeys();
                    int id = 0;
                    if (keys.next()) {
                        id = keys.getInt(1);
                    }
                    conn.commit();
                    logger.info("Sync manifest saved: id={}, project={}, service={}, resource={}",
                            id, manifest.projectId(), manifest.serviceId(), manifest.resourcePath());
                    return id;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * Get all sync manifests for a project.
     *
     * @param projectId the project identifier
     * @return list of manifest maps (all columns)
     */
    public List<Map<String, Object>> getAll(String projectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT * FROM sync_manifests WHERE project_id = ? ORDER BY synced_at DESC")) {
            stmt.setString(1, projectId);
            return resultSetToList(stmt.executeQuery());
        }
    }

    /**
     * Get sync manifests filtered by project and service.
     *
     * @param projectId the project identifier
     * @param serviceId the service identifier (e.g., "bigquery", "firestore")
     * @return list of manifest maps for the given service
     */
    public List<Map<String, Object>> getByService(String projectId, String serviceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT * FROM sync_manifests WHERE project_id = ? AND service_id = ? ORDER BY synced_at DESC")) {
            stmt.setString(1, projectId);
            stmt.setString(2, serviceId);
            return resultSetToList(stmt.executeQuery());
        }
    }

    /**
     * Get a single sync manifest by id.
     *
     * @param id the manifest id
     * @return manifest map or null if not found
     */
    public Map<String, Object> getById(int id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT * FROM sync_manifests WHERE id = ?")) {
            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return resultSetRowToMap(rs);
            }
        }
        return null;
    }

    /**
     * Update sync progress for a manifest (status, row count, bytes synced, error message).
     *
     * @param id           the manifest id
     * @param status       new status (e.g., "syncing", "completed", "failed")
     * @param rowCount     number of rows synced so far
     * @param bytesSynced  bytes synced so far
     * @param errorMessage error message (null if no error)
     */
    public void updateProgress(int id, String status, long rowCount, long bytesSynced,
                               String errorMessage) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "UPDATE sync_manifests SET status = ?, row_count = ?, bytes_synced = ?, " +
                 "error_message = ?, synced_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            stmt.setString(1, status);
            stmt.setLong(2, rowCount);
            stmt.setLong(3, bytesSynced);
            stmt.setString(4, errorMessage);
            stmt.setInt(5, id);
            stmt.executeUpdate();
        }
    }

    /**
     * Delete a sync manifest by id.
     *
     * @param id the manifest id
     */
    public void delete(int id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "DELETE FROM sync_manifests WHERE id = ?")) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
        logger.info("Sync manifest deleted: id={}", id);
    }

    /**
     * Convert a ResultSet to a list of maps (one map per row).
     */
    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        while (rs.next()) {
            results.add(resultSetRowToMap(rs));
        }
        return results;
    }

    /**
     * Convert current ResultSet row to a map of column name to value.
     */
    private Map<String, Object> resultSetRowToMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= columnCount; i++) {
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }
}
