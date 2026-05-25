package com.localcloud.emulators.alloydb;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.google.cloud.alloydb.v1.Backup;
import com.google.cloud.alloydb.v1.Cluster;
import com.google.cloud.alloydb.v1.Instance;
import com.google.cloud.alloydb.v1.User;
import com.localcloud.emulators.common.GrpcSupport;
import com.localcloud.persistence.PostgresDataSource;

public class AlloyDBRepository {
    private final PostgresDataSource dataSource;

    public AlloyDBRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    private void createSchema() {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            String jsonType = conn.getMetaData().getURL().contains(":h2:") ? "TEXT" : "JSONB";
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS alloydb_clusters (
                        project_id VARCHAR(255) NOT NULL,
                        location_id VARCHAR(255) NOT NULL,
                        cluster_id VARCHAR(255) NOT NULL,
                        database_name VARCHAR(255) NOT NULL,
                        metadata %s DEFAULT '{}',
                        cluster_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location_id, cluster_id)
                    )
                    """.formatted(jsonType));
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS alloydb_instances (
                        project_id VARCHAR(255) NOT NULL,
                        location_id VARCHAR(255) NOT NULL,
                        cluster_id VARCHAR(255) NOT NULL,
                        instance_id VARCHAR(255) NOT NULL,
                        metadata %s DEFAULT '{}',
                        instance_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location_id, cluster_id, instance_id),
                        FOREIGN KEY (project_id, location_id, cluster_id)
                          REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
                    )
                    """.formatted(jsonType));
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS alloydb_databases (
                        project_id VARCHAR(255) NOT NULL,
                        location_id VARCHAR(255) NOT NULL,
                        cluster_id VARCHAR(255) NOT NULL,
                        database_name VARCHAR(255) NOT NULL,
                        physical_name VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location_id, cluster_id, database_name),
                        FOREIGN KEY (project_id, location_id, cluster_id)
                          REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS alloydb_backups (
                        project_id VARCHAR(255) NOT NULL,
                        location_id VARCHAR(255) NOT NULL,
                        backup_id VARCHAR(255) NOT NULL,
                        cluster_name VARCHAR(1024) NOT NULL,
                        metadata %s DEFAULT '{}',
                        backup_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location_id, backup_id)
                    )
                    """.formatted(jsonType));
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS alloydb_users (
                        project_id VARCHAR(255) NOT NULL,
                        location_id VARCHAR(255) NOT NULL,
                        cluster_id VARCHAR(255) NOT NULL,
                        user_id VARCHAR(255) NOT NULL,
                        metadata %s DEFAULT '{}',
                        user_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location_id, cluster_id, user_id),
                        FOREIGN KEY (project_id, location_id, cluster_id)
                          REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
                    )
                    """.formatted(jsonType));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize AlloyDB schema", e);
        }
    }

    public boolean clusterExists(String projectId, String locationId, String clusterId) throws SQLException {
        return exists("alloydb_clusters", "cluster_id", projectId, locationId, clusterId, null);
    }

    public void createCluster(String projectId, String locationId, String clusterId, Cluster cluster) throws SQLException {
        String databaseName = GrpcSupport.safeDatabaseName(clusterId);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO alloydb_clusters
                     (project_id, location_id, cluster_id, database_name, cluster_proto)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, clusterId);
            ps.setString(4, databaseName);
            ps.setBytes(5, cluster.toByteArray());
            ps.executeUpdate();
        }
        createDatabaseMetadata(projectId, locationId, clusterId, databaseName, databaseName);
        createDatabase(databaseName);
    }

    private void createDatabaseMetadata(String projectId, String locationId, String clusterId,
                                        String databaseName, String physicalName) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement find = conn.prepareStatement("""
                    SELECT 1 FROM alloydb_databases
                    WHERE project_id = ? AND location_id = ? AND cluster_id = ? AND database_name = ?
                    """)) {
                find.setString(1, projectId);
                find.setString(2, locationId);
                find.setString(3, clusterId);
                find.setString(4, databaseName);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) return;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO alloydb_databases
                     (project_id, location_id, cluster_id, database_name, physical_name)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
                ps.setString(1, projectId);
                ps.setString(2, locationId);
                ps.setString(3, clusterId);
                ps.setString(4, databaseName);
                ps.setString(5, physicalName);
                ps.executeUpdate();
            }
        }
    }

    public Cluster getCluster(String projectId, String locationId, String clusterId) throws SQLException {
        return queryOne("SELECT cluster_proto FROM alloydb_clusters WHERE project_id=? AND location_id=? AND cluster_id=?",
                rs -> Cluster.parseFrom(rs.getBytes(1)), projectId, locationId, clusterId);
    }

    public List<Cluster> listClusters(String projectId, String locationId) throws SQLException {
        return queryList("SELECT cluster_proto FROM alloydb_clusters WHERE project_id=? AND location_id=? ORDER BY cluster_id",
                rs -> Cluster.parseFrom(rs.getBytes(1)), projectId, locationId);
    }

    public boolean deleteCluster(String projectId, String locationId, String clusterId) throws SQLException {
        String databaseName;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement find = conn.prepareStatement(
                     "SELECT database_name FROM alloydb_clusters WHERE project_id=? AND location_id=? AND cluster_id=?")) {
            find.setString(1, projectId);
            find.setString(2, locationId);
            find.setString(3, clusterId);
            try (ResultSet rs = find.executeQuery()) {
                if (!rs.next()) return false;
                databaseName = rs.getString(1);
            }
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM alloydb_clusters WHERE project_id=? AND location_id=? AND cluster_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, clusterId);
            ps.executeUpdate();
        }
        try {
            dropDatabase(databaseName);
        } catch (Exception e) {
            throw new SQLException("Cluster deleted but failed to drop database: " + databaseName, e);
        }
        return true;
    }

    public void createInstance(String projectId, String locationId, String clusterId, String instanceId, Instance instance)
            throws SQLException {
        insertChild("alloydb_instances", "instance_id", "instance_proto", projectId, locationId, clusterId, instanceId,
                instance.toByteArray());
    }

    public Instance getInstance(String projectId, String locationId, String clusterId, String instanceId) throws SQLException {
        return queryOne("""
                SELECT instance_proto FROM alloydb_instances
                WHERE project_id=? AND location_id=? AND cluster_id=? AND instance_id=?
                """, rs -> Instance.parseFrom(rs.getBytes(1)), projectId, locationId, clusterId, instanceId);
    }

    public List<Instance> listInstances(String projectId, String locationId, String clusterId) throws SQLException {
        return queryList("""
                SELECT instance_proto FROM alloydb_instances
                WHERE project_id=? AND location_id=? AND cluster_id=?
                ORDER BY instance_id
                """, rs -> Instance.parseFrom(rs.getBytes(1)), projectId, locationId, clusterId);
    }

    public void createBackup(String projectId, String locationId, String backupId, String clusterName, Backup backup)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO alloydb_backups
                     (project_id, location_id, backup_id, cluster_name, backup_proto)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, backupId);
            ps.setString(4, clusterName);
            ps.setBytes(5, backup.toByteArray());
            ps.executeUpdate();
        }
    }

    public Backup getBackup(String projectId, String locationId, String backupId) throws SQLException {
        return queryOne("SELECT backup_proto FROM alloydb_backups WHERE project_id=? AND location_id=? AND backup_id=?",
                rs -> Backup.parseFrom(rs.getBytes(1)), projectId, locationId, backupId);
    }

    public List<Backup> listBackups(String projectId, String locationId) throws SQLException {
        return queryList("SELECT backup_proto FROM alloydb_backups WHERE project_id=? AND location_id=? ORDER BY backup_id",
                rs -> Backup.parseFrom(rs.getBytes(1)), projectId, locationId);
    }

    public void createUser(String projectId, String locationId, String clusterId, String userId, User user)
            throws SQLException {
        insertChild("alloydb_users", "user_id", "user_proto", projectId, locationId, clusterId, userId,
                user.toByteArray());
    }

    public User getUser(String projectId, String locationId, String clusterId, String userId) throws SQLException {
        return queryOne("""
                SELECT user_proto FROM alloydb_users
                WHERE project_id=? AND location_id=? AND cluster_id=? AND user_id=?
                """, rs -> User.parseFrom(rs.getBytes(1)), projectId, locationId, clusterId, userId);
    }

    public List<User> listUsers(String projectId, String locationId, String clusterId) throws SQLException {
        return queryList("""
                SELECT user_proto FROM alloydb_users
                WHERE project_id=? AND location_id=? AND cluster_id=?
                ORDER BY user_id
                """, rs -> User.parseFrom(rs.getBytes(1)), projectId, locationId, clusterId);
    }

    public boolean deleteUser(String projectId, String locationId, String clusterId, String userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     DELETE FROM alloydb_users
                     WHERE project_id=? AND location_id=? AND cluster_id=? AND user_id=?
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, clusterId);
            ps.setString(4, userId);
            return ps.executeUpdate() > 0;
        }
    }

    private boolean exists(String table, String idColumn, String projectId, String locationId, String id, String clusterId)
            throws SQLException {
        String sql = clusterId == null
                ? "SELECT 1 FROM " + table + " WHERE project_id=? AND location_id=? AND " + idColumn + "=?"
                : "SELECT 1 FROM " + table + " WHERE project_id=? AND location_id=? AND cluster_id=? AND " + idColumn + "=?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            if (clusterId == null) {
                ps.setString(3, id);
            } else {
                ps.setString(3, clusterId);
                ps.setString(4, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertChild(String table, String idColumn, String protoColumn, String projectId, String locationId,
                             String clusterId, String id, byte[] proto) throws SQLException {
        String sql = "INSERT INTO " + table + " (project_id, location_id, cluster_id, " + idColumn + ", " + protoColumn
                + ") VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, clusterId);
            ps.setString(4, id);
            ps.setBytes(5, proto);
            ps.executeUpdate();
        }
    }

    private <T> T queryOne(String sql, Parser<T> parser, String... args) throws SQLException {
        List<T> rows = queryList(sql, parser, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> List<T> queryList(String sql, Parser<T> parser, String... args) throws SQLException {
        List<T> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        rows.add(parser.parse(rs));
                    } catch (Exception e) {
                        throw new SQLException("Failed to parse AlloyDB proto", e);
                    }
                }
            }
        }
        return rows;
    }

    private void createDatabase(String databaseName) {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + databaseName);
        } catch (Exception ignored) {
            // Some tests use H2 and some local setups may not allow CREATE DATABASE.
        }
        try (Connection conn = dataSource.getConnection(databaseName); var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (Exception ignored) {
            // pgvector is optional in local PostgreSQL installations.
        }
    }

    private void dropDatabase(String databaseName) {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + databaseName);
        } catch (SQLException ignored) {
        }
    }

    private interface Parser<T> {
        T parse(ResultSet rs) throws Exception;
    }
}
