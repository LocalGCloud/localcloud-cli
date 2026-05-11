package com.localcloud.license.auth;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

public class AuthRepository {

    private final DataSource dataSource;

    public AuthRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public UUID createUser(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (email) VALUES (?)")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            // SQLState 23xxx = integrity constraint violation (duplicate key)
            // This is expected when email already exists — treat as upsert
            if (!e.getSQLState().startsWith("23")) {
                throw e;
            }
        }
        return getUserId(email);
    }

    public UUID getUserId(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM users WHERE email = ?")) {
            ps.setString(1, email.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    public boolean userExists(String email) throws SQLException {
        return getUserId(email) != null;
    }

    public boolean isEmailVerified(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT email_verified FROM users WHERE email = ?")) {
            ps.setString(1, email.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    public void markEmailVerified(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE users SET email_verified = TRUE WHERE email = ?")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.executeUpdate();
        }
    }

    public String getUserEmail(UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT email FROM users WHERE id = ?")) {
            ps.setString(1, userId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
