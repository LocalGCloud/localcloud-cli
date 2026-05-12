package com.localcloud.license.trial;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class TrialRepository {

    private final DataSource dataSource;
    private final int trialDays;

    public TrialRepository(DataSource dataSource, int trialDays) {
        this.dataSource = dataSource;
        this.trialDays = trialDays;
    }

    public boolean startTrial(UUID userId, String deviceFingerprint) throws SQLException {
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(trialDays, ChronoUnit.DAYS));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO trials (user_id, device_fingerprint, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, userId.toString());
            ps.setString(2, deviceFingerprint);
            ps.setTimestamp(3, expiresAt);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                return false; // Device already had a trial
            }
            throw e;
        }
    }

    public TrialInfo getTrialInfo(UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT started_at, expires_at FROM trials WHERE user_id = ? ORDER BY started_at DESC LIMIT 1")) {
            ps.setString(1, userId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TrialInfo(
                    rs.getTimestamp(1).toInstant().getEpochSecond(),
                    rs.getTimestamp(2).toInstant().getEpochSecond());
            }
        }
    }

    public boolean deviceHasUsedTrial(String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM trials WHERE device_fingerprint = ?")) {
            ps.setString(1, deviceFingerprint);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public record TrialInfo(long startedAt, long expiresAt) {}
}
