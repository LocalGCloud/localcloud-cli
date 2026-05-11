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
                 "INSERT INTO devices (user_id, device_fingerprint) VALUES (?, ?)")) {
            ps.setString(1, userId.toString());
            ps.setString(2, deviceFingerprint);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                // Already exists — update last_seen
                try (Connection conn2 = dataSource.getConnection();
                     PreparedStatement ps2 = conn2.prepareStatement(
                         "UPDATE devices SET last_seen = NOW() WHERE user_id = ? AND device_fingerprint = ?")) {
                    ps2.setString(1, userId.toString());
                    ps2.setString(2, deviceFingerprint);
                    ps2.executeUpdate();
                }
            } else throw e;
        }
    }
}
