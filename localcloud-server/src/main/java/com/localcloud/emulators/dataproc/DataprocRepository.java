package com.localcloud.emulators.dataproc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.google.cloud.dataproc.v1.Cluster;
import com.google.cloud.dataproc.v1.Job;
import com.localcloud.persistence.PostgresDataSource;

public class DataprocRepository {
    private final PostgresDataSource dataSource;

    public DataprocRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    private void createSchema() {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            String jsonType = conn.getMetaData().getURL().contains(":h2:") ? "TEXT" : "JSONB";
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dataproc_clusters (
                        project_id VARCHAR(255) NOT NULL,
                        region VARCHAR(255) NOT NULL,
                        cluster_name VARCHAR(255) NOT NULL,
                        metadata %s DEFAULT '{}',
                        cluster_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, region, cluster_name)
                    )
                    """.formatted(jsonType));
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dataproc_jobs (
                        project_id VARCHAR(255) NOT NULL,
                        region VARCHAR(255) NOT NULL,
                        job_id VARCHAR(255) NOT NULL,
                        cluster_name VARCHAR(255) NOT NULL,
                        status VARCHAR(64) NOT NULL,
                        driver_output_path VARCHAR(2048) DEFAULT '',
                        job_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, region, job_id)
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize Dataproc schema", e);
        }
    }

    public boolean clusterExists(String projectId, String region, String clusterName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM dataproc_clusters WHERE project_id=? AND region=? AND cluster_name=?")) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            ps.setString(3, clusterName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void createCluster(String projectId, String region, String clusterName, Cluster cluster) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO dataproc_clusters (project_id, region, cluster_name, cluster_proto) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            ps.setString(3, clusterName);
            ps.setBytes(4, cluster.toByteArray());
            ps.executeUpdate();
        }
    }

    public Cluster getCluster(String projectId, String region, String clusterName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT cluster_proto FROM dataproc_clusters WHERE project_id=? AND region=? AND cluster_name=?")) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            ps.setString(3, clusterName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Cluster.parseFrom(rs.getBytes(1));
            } catch (Exception e) {
                throw new SQLException("Failed to parse Dataproc cluster", e);
            }
        }
    }

    public List<Cluster> listClusters(String projectId, String region) throws SQLException {
        List<Cluster> clusters = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT cluster_proto FROM dataproc_clusters
                     WHERE project_id=? AND region=?
                     ORDER BY cluster_name
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) clusters.add(Cluster.parseFrom(rs.getBytes(1)));
            } catch (Exception e) {
                throw new SQLException("Failed to parse Dataproc cluster", e);
            }
        }
        return clusters;
    }

    public boolean deleteCluster(String projectId, String region, String clusterName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM dataproc_clusters WHERE project_id=? AND region=? AND cluster_name=?")) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            ps.setString(3, clusterName);
            return ps.executeUpdate() > 0;
        }
    }

    public void createJob(String projectId, String region, String jobId, String clusterName, String status,
                          String driverOutputPath, Job job) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO dataproc_jobs
                     (project_id, region, job_id, cluster_name, status, driver_output_path, job_proto)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            ps.setString(3, jobId);
            ps.setString(4, clusterName);
            ps.setString(5, status);
            ps.setString(6, driverOutputPath);
            ps.setBytes(7, job.toByteArray());
            ps.executeUpdate();
        }
    }

    public boolean updateJob(String projectId, String region, String jobId, String status, Job job) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE dataproc_jobs
                     SET status=?, job_proto=?, updated_at=CURRENT_TIMESTAMP
                     WHERE project_id=? AND region=? AND job_id=?
                     """)) {
            ps.setString(1, status);
            ps.setBytes(2, job.toByteArray());
            ps.setString(3, projectId);
            ps.setString(4, region);
            ps.setString(5, jobId);
            return ps.executeUpdate() > 0;
        }
    }

    public Job getJob(String projectId, String region, String jobId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT job_proto FROM dataproc_jobs WHERE project_id=? AND region=? AND job_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            ps.setString(3, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Job.parseFrom(rs.getBytes(1));
            } catch (Exception e) {
                throw new SQLException("Failed to parse Dataproc job", e);
            }
        }
    }

    public List<Job> listJobs(String projectId, String region) throws SQLException {
        List<Job> jobs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT job_proto FROM dataproc_jobs WHERE project_id=? AND region=? ORDER BY job_id")) {
            ps.setString(1, projectId);
            ps.setString(2, region);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) jobs.add(Job.parseFrom(rs.getBytes(1)));
            } catch (Exception e) {
                throw new SQLException("Failed to parse Dataproc job", e);
            }
        }
        return jobs;
    }
}
