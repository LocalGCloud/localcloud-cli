package com.localcloud.license.validation;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

public class DeviceTracker {

    private final DataSource dataSource;

    public DeviceTracker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void recordDevice(UUID userId, String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO devices (user_id, device_fingerprint) VALUES (?, ?) " +
                 "ON CONFLICT (user_id, device_fingerprint) DO UPDATE SET last_seen = NOW()")) {
            ps.setString(1, userId.toString());
            ps.setString(2, deviceFingerprint);
            ps.executeUpdate();
        }
    }

    public boolean deviceKnown(UUID userId, String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM devices WHERE user_id = ? AND device_fingerprint = ?")) {
            ps.setString(1, userId.toString());
            ps.setString(2, deviceFingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
