package com.localcloud.emulators.gke;

import com.localcloud.persistence.PostgresDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for GKE clusters backed by PostgreSQL.
 */
public class GkeStore {

    private final PostgresDataSource dataSource;

    public GkeStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Cluster(
            String projectId,
            String location,
            String clusterId,
            String status,
            String k3dClusterName,
            String endpoint,
            String clusterVersion,
            int nodeCount,
            String kubeconfig,
            Timestamp createdAt
    ) {}

    public void insertCluster(Cluster cluster) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO gke_clusters(project_id, location, cluster_id, status, k3d_cluster_name, endpoint, cluster_version, node_count, kubeconfig) " +
                     "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, cluster.projectId());
            ps.setString(2, cluster.location());
            ps.setString(3, cluster.clusterId());
            ps.setString(4, cluster.status());
            ps.setString(5, cluster.k3dClusterName());
            ps.setString(6, cluster.endpoint());
            ps.setString(7, cluster.clusterVersion());
            ps.setInt(8, cluster.nodeCount());
            ps.setString(9, cluster.kubeconfig());
            ps.executeUpdate();
        }
    }

    public Optional<Cluster> getCluster(String projectId, String location, String clusterId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM gke_clusters WHERE project_id=? AND location=? AND cluster_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, location);
            ps.setString(3, clusterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rowToCluster(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<Cluster> listClusters(String projectId, String location) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM gke_clusters WHERE project_id=? AND location=? ORDER BY created_at")) {
            ps.setString(1, projectId);
            ps.setString(2, location);
            try (ResultSet rs = ps.executeQuery()) {
                List<Cluster> clusters = new ArrayList<>();
                while (rs.next()) {
                    clusters.add(rowToCluster(rs));
                }
                return clusters;
            }
        }
    }

    public void updateStatus(String projectId, String location, String clusterId, String status) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE gke_clusters SET status=? WHERE project_id=? AND location=? AND cluster_id=?")) {
            ps.setString(1, status);
            ps.setString(2, projectId);
            ps.setString(3, location);
            ps.setString(4, clusterId);
            ps.executeUpdate();
        }
    }

    public void deleteCluster(String projectId, String location, String clusterId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM gke_clusters WHERE project_id=? AND location=? AND cluster_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, location);
            ps.setString(3, clusterId);
            ps.executeUpdate();
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM gke_clusters");
        }
    }

    private Cluster rowToCluster(ResultSet rs) throws SQLException {
        return new Cluster(
                rs.getString("project_id"),
                rs.getString("location"),
                rs.getString("cluster_id"),
                rs.getString("status"),
                rs.getString("k3d_cluster_name"),
                rs.getString("endpoint"),
                rs.getString("cluster_version"),
                rs.getInt("node_count"),
                rs.getString("kubeconfig"),
                rs.getTimestamp("created_at")
        );
    }
}
