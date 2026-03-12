package com.localcloud.emulators.cloudrun;

import com.localcloud.persistence.PostgresDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence layer for Cloud Run services and revisions.
 */
public class CloudRunStore {

    private final PostgresDataSource dataSource;

    public CloudRunStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record Service(
            String projectId,
            String location,
            String serviceId,
            String containerImage,
            int containerPort,
            String containerId,
            Integer hostPort,
            String uri,
            String envVars,
            int revisionCount,
            Timestamp createdAt,
            Timestamp updatedAt
    ) {}

    public record Revision(
            String projectId,
            String location,
            String serviceId,
            String revisionId,
            String containerImage,
            String containerId,
            Timestamp createdAt
    ) {}

    // --- Service CRUD ---

    public void insertService(Service service) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudrun_services(project_id, location, service_id, container_image, container_port, container_id, host_port, uri, env_vars) " +
                     "VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, service.projectId());
            ps.setString(2, service.location());
            ps.setString(3, service.serviceId());
            ps.setString(4, service.containerImage());
            ps.setInt(5, service.containerPort());
            ps.setString(6, service.containerId());
            if (service.hostPort() != null) {
                ps.setInt(7, service.hostPort());
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.setString(8, service.uri());
            ps.setString(9, service.envVars());
            ps.executeUpdate();
        }
    }

    public Optional<Service> getService(String projectId, String location, String serviceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM cloudrun_services WHERE project_id=? AND location=? AND service_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, location);
            ps.setString(3, serviceId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(rowToService(rs));
            }
            return Optional.empty();
        }
    }

    public List<Service> listServices(String projectId, String location) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM cloudrun_services WHERE project_id=? AND location=? ORDER BY created_at")) {
            ps.setString(1, projectId);
            ps.setString(2, location);
            ResultSet rs = ps.executeQuery();
            List<Service> services = new ArrayList<>();
            while (rs.next()) {
                services.add(rowToService(rs));
            }
            return services;
        }
    }

    public void updateService(String projectId, String location, String serviceId,
                              String containerImage, String containerId, Integer hostPort, String uri) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE cloudrun_services SET container_image=?, container_id=?, host_port=?, uri=?, " +
                     "revision_count=revision_count+1, updated_at=CURRENT_TIMESTAMP " +
                     "WHERE project_id=? AND location=? AND service_id=?")) {
            ps.setString(1, containerImage);
            ps.setString(2, containerId);
            if (hostPort != null) {
                ps.setInt(3, hostPort);
            } else {
                ps.setNull(3, Types.INTEGER);
            }
            ps.setString(4, uri);
            ps.setString(5, projectId);
            ps.setString(6, location);
            ps.setString(7, serviceId);
            ps.executeUpdate();
        }
    }

    public void deleteService(String projectId, String location, String serviceId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM cloudrun_revisions WHERE project_id=? AND location=? AND service_id=?")) {
                ps.setString(1, projectId);
                ps.setString(2, location);
                ps.setString(3, serviceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM cloudrun_services WHERE project_id=? AND location=? AND service_id=?")) {
                ps.setString(1, projectId);
                ps.setString(2, location);
                ps.setString(3, serviceId);
                ps.executeUpdate();
            }
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM cloudrun_revisions");
            stmt.execute("DELETE FROM cloudrun_services");
        }
    }

    // --- Revision CRUD ---

    public void insertRevision(Revision revision) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cloudrun_revisions(project_id, location, service_id, revision_id, container_image, container_id) " +
                     "VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, revision.projectId());
            ps.setString(2, revision.location());
            ps.setString(3, revision.serviceId());
            ps.setString(4, revision.revisionId());
            ps.setString(5, revision.containerImage());
            ps.setString(6, revision.containerId());
            ps.executeUpdate();
        }
    }

    public Optional<Revision> getRevision(String projectId, String location, String serviceId, String revisionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM cloudrun_revisions WHERE project_id=? AND location=? AND service_id=? AND revision_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, location);
            ps.setString(3, serviceId);
            ps.setString(4, revisionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(rowToRevision(rs));
            }
            return Optional.empty();
        }
    }

    public List<Revision> listRevisions(String projectId, String location, String serviceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM cloudrun_revisions WHERE project_id=? AND location=? AND service_id=? ORDER BY created_at")) {
            ps.setString(1, projectId);
            ps.setString(2, location);
            ps.setString(3, serviceId);
            ResultSet rs = ps.executeQuery();
            List<Revision> revisions = new ArrayList<>();
            while (rs.next()) {
                revisions.add(rowToRevision(rs));
            }
            return revisions;
        }
    }

    private Service rowToService(ResultSet rs) throws SQLException {
        return new Service(
                rs.getString("project_id"),
                rs.getString("location"),
                rs.getString("service_id"),
                rs.getString("container_image"),
                rs.getInt("container_port"),
                rs.getString("container_id"),
                rs.getObject("host_port") != null ? rs.getInt("host_port") : null,
                rs.getString("uri"),
                rs.getString("env_vars"),
                rs.getInt("revision_count"),
                rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at")
        );
    }

    private Revision rowToRevision(ResultSet rs) throws SQLException {
        return new Revision(
                rs.getString("project_id"),
                rs.getString("location"),
                rs.getString("service_id"),
                rs.getString("revision_id"),
                rs.getString("container_image"),
                rs.getString("container_id"),
                rs.getTimestamp("created_at")
        );
    }
}
