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

    public boolean trialExists(String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM trials WHERE device_fingerprint = ?")) {
            ps.setString(1, deviceFingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean isTrialActive(String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT expires_at FROM trials WHERE device_fingerprint = ?")) {
            ps.setString(1, deviceFingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return rs.getTimestamp(1).toInstant().isAfter(Instant.now());
            }
        }
    }

    public void startTrial(UUID userId, String deviceFingerprint) throws SQLException {
        Timestamp expires = Timestamp.from(Instant.now().plus(trialDays, ChronoUnit.DAYS));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO trials (user_id, device_fingerprint, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, userId.toString());
            ps.setString(2, deviceFingerprint);
            ps.setTimestamp(3, expires);
            ps.executeUpdate();
        }
    }
}
