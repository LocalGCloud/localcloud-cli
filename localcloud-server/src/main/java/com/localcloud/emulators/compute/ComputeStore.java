package com.localcloud.emulators.compute;

import com.localcloud.persistence.PostgresDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for Compute Engine instances backed by PostgreSQL.
 */
public class ComputeStore {

    private final PostgresDataSource dataSource;

    public ComputeStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Instance(
            String projectId,
            String zone,
            String instanceName,
            String machineType,
            String status,
            String containerId,
            String containerImage,
            String networkIp,
            String metadata,
            Timestamp createdAt
    ) {}

    public void insertInstance(Instance instance) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO compute_instances(project_id, zone, instance_name, machine_type, status, container_id, container_image, network_ip, metadata) " +
                     "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, instance.projectId());
            ps.setString(2, instance.zone());
            ps.setString(3, instance.instanceName());
            ps.setString(4, instance.machineType());
            ps.setString(5, instance.status());
            ps.setString(6, instance.containerId());
            ps.setString(7, instance.containerImage());
            ps.setString(8, instance.networkIp());
            ps.setString(9, instance.metadata());
            ps.executeUpdate();
        }
    }

    public Optional<Instance> getInstance(String projectId, String zone, String instanceName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM compute_instances WHERE project_id=? AND zone=? AND instance_name=?")) {
            ps.setString(1, projectId);
            ps.setString(2, zone);
            ps.setString(3, instanceName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rowToInstance(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<Instance> listInstances(String projectId, String zone) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM compute_instances WHERE project_id=? AND zone=? ORDER BY created_at")) {
            ps.setString(1, projectId);
            ps.setString(2, zone);
            try (ResultSet rs = ps.executeQuery()) {
                List<Instance> instances = new ArrayList<>();
                while (rs.next()) {
                    instances.add(rowToInstance(rs));
                }
                return instances;
            }
        }
    }

    public void updateStatus(String projectId, String zone, String instanceName, String status) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE compute_instances SET status=? WHERE project_id=? AND zone=? AND instance_name=?")) {
            ps.setString(1, status);
            ps.setString(2, projectId);
            ps.setString(3, zone);
            ps.setString(4, instanceName);
            ps.executeUpdate();
        }
    }

    public void updateContainerId(String projectId, String zone, String instanceName, String containerId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE compute_instances SET container_id=? WHERE project_id=? AND zone=? AND instance_name=?")) {
            ps.setString(1, containerId);
            ps.setString(2, projectId);
            ps.setString(3, zone);
            ps.setString(4, instanceName);
            ps.executeUpdate();
        }
    }

    public void deleteInstance(String projectId, String zone, String instanceName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM compute_instances WHERE project_id=? AND zone=? AND instance_name=?")) {
            ps.setString(1, projectId);
            ps.setString(2, zone);
            ps.setString(3, instanceName);
            ps.executeUpdate();
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM compute_instances");
        }
    }

    private Instance rowToInstance(ResultSet rs) throws SQLException {
        return new Instance(
                rs.getString("project_id"),
                rs.getString("zone"),
                rs.getString("instance_name"),
                rs.getString("machine_type"),
                rs.getString("status"),
                rs.getString("container_id"),
                rs.getString("container_image"),
                rs.getString("network_ip"),
                rs.getString("metadata"),
                rs.getTimestamp("created_at")
        );
    }
}
