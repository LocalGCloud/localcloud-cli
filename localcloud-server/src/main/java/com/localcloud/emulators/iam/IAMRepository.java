package com.localcloud.emulators.iam;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.google.iam.v1.Policy;
import com.localcloud.persistence.PostgresDataSource;

public class IAMRepository {
    private final PostgresDataSource dataSource;

    public IAMRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    private void createSchema() {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            String jsonType = conn.getMetaData().getURL().contains(":h2:") ? "TEXT" : "JSONB";
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS iam_policies (
                        resource_type VARCHAR(255) NOT NULL,
                        resource_id VARCHAR(1024) NOT NULL,
                        policy %s DEFAULT '{}',
                        policy_proto BYTEA NOT NULL,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (resource_type, resource_id)
                    )
                    """.formatted(jsonType));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize IAM schema", e);
        }
    }

    public Policy get(String resource) throws SQLException {
        String[] parts = split(resource);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT policy_proto FROM iam_policies WHERE resource_type=? AND resource_id=?")) {
            ps.setString(1, parts[0]);
            ps.setString(2, parts[1]);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Policy.getDefaultInstance();
                return Policy.parseFrom(rs.getBytes("policy_proto"));
            } catch (Exception e) {
                throw new SQLException("Failed to parse IAM policy", e);
            }
        }
    }

    public Policy set(String resource, Policy policy) throws SQLException {
        String[] parts = split(resource);
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement update = conn.prepareStatement("""
                    UPDATE iam_policies
                    SET policy_proto=?, updated_at=CURRENT_TIMESTAMP
                    WHERE resource_type=? AND resource_id=?
                    """)) {
                update.setBytes(1, policy.toByteArray());
                update.setString(2, parts[0]);
                update.setString(3, parts[1]);
                if (update.executeUpdate() > 0) return policy;
            }
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO iam_policies (resource_type, resource_id, policy_proto) VALUES (?, ?, ?)")) {
                insert.setString(1, parts[0]);
                insert.setString(2, parts[1]);
                insert.setBytes(3, policy.toByteArray());
                insert.executeUpdate();
            }
            return policy;
        }
    }

    private static String[] split(String resource) {
        int index = resource.indexOf('/');
        if (index <= 0) return new String[] {"resource", resource};
        return new String[] {resource.substring(0, index), resource.substring(index + 1)};
    }
}
