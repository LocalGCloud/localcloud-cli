package com.localcloud.license.auth;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class SessionRepository {
    private static final int SESSION_HOURS = 1;
    private final DataSource dataSource;

    public SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates a new session for userId. Returns the raw session token (UUID string). */
    public String createSession(UUID userId) throws SQLException {
        String token = UUID.randomUUID().toString();
        Timestamp expires = Timestamp.from(Instant.now().plus(SESSION_HOURS, ChronoUnit.HOURS));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, token);
            ps.setString(2, userId.toString());
            ps.setTimestamp(3, expires);
            ps.executeUpdate();
        }
        return token;
    }

    /** Returns userId if token is valid and not expired, null otherwise. */
    public UUID validateSession(String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT user_id FROM sessions WHERE token = ? AND expires_at > NOW()")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return UUID.fromString(rs.getString("user_id"));
            }
        }
    }

    /** Deletes the session (logout). */
    public void expireSession(String token) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        }
    }
}
