package com.localcloud.license.admin;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class AdminStatsRepository {

    private final DataSource ds;

    public AdminStatsRepository(DataSource dataSource) {
        this.ds = dataSource;
    }

    public Map<String, Object> getStats() {
        try (Connection conn = ds.getConnection()) {
            long totalKeys = queryLong(conn, "SELECT COUNT(*) FROM api_keys");
            long activeKeys = queryLong(conn, "SELECT COUNT(*) FROM api_keys WHERE revoked_at IS NULL");
            long expiredKeys = queryLong(conn, "SELECT COUNT(*) FROM api_keys WHERE expires_at IS NOT NULL AND expires_at < NOW()");
            long totalUsers = queryLong(conn, "SELECT COUNT(*) FROM users");
            long verifiedUsers = queryLong(conn, "SELECT COUNT(*) FROM users WHERE email_verified = TRUE");
            long activeTrials = queryLong(conn, "SELECT COUNT(*) FROM trials WHERE expires_at > NOW()");
            long expiredTrials = queryLong(conn, "SELECT COUNT(*) FROM trials WHERE expires_at <= NOW()");
            long totalDevices = queryLong(conn, "SELECT COUNT(*) FROM devices");
            long keysByTierPro = queryLong(conn, "SELECT COUNT(*) FROM api_keys WHERE tier = 'pro' AND revoked_at IS NULL");
            long keysByTierTrial = queryLong(conn, "SELECT COUNT(*) FROM api_keys WHERE tier = 'trial' AND revoked_at IS NULL");
            long keysByTierCommunity = queryLong(conn, "SELECT COUNT(*) FROM api_keys WHERE tier = 'community' AND revoked_at IS NULL");
            long keysOnline = queryLong(conn, "SELECT COUNT(*) FROM api_keys WHERE mode = 'online' AND revoked_at IS NULL");
            long keysOffline = queryLong(conn, "SELECT COUNT(*) FROM api_keys WHERE mode = 'offline' AND revoked_at IS NULL");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total_keys", totalKeys);
            m.put("active_keys", activeKeys);
            m.put("expired_keys", expiredKeys);
            m.put("keys_online", keysOnline);
            m.put("keys_offline", keysOffline);
            m.put("total_users", totalUsers);
            m.put("verified_users", verifiedUsers);
            m.put("active_trials", activeTrials);
            m.put("expired_trials", expiredTrials);
            m.put("total_devices", totalDevices);
            m.put("keys_pro", keysByTierPro);
            m.put("keys_trial", keysByTierTrial);
            m.put("keys_community", keysByTierCommunity);
            return m;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve stats", e);
        }
    }

    private long queryLong(Connection conn, String sql) throws Exception {
        try (var ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
