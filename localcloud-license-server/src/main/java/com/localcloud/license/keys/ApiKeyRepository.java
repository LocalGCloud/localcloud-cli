package com.localcloud.license.keys;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

public class ApiKeyRepository {

    private final DataSource dataSource;

    public ApiKeyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean keyExists(String keyHash) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM api_keys WHERE key_hash = ? AND revoked_at IS NULL")) {
            ps.setString(1, keyHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public UUID getUserIdForKey(String keyHash) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT user_id FROM api_keys WHERE key_hash = ? AND revoked_at IS NULL")) {
            ps.setString(1, keyHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    public String getTierForKey(String keyHash) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tier FROM api_keys WHERE key_hash = ? AND revoked_at IS NULL")) {
            ps.setString(1, keyHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
