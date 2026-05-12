package com.localcloud.license.keys;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class ApiKeyRepository {

    private final DataSource dataSource;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String generateOnlineKey(UUID userId, String tier) throws Exception {
        byte[] rawBytes = new byte[32];
        random.nextBytes(rawBytes);
        String rawKey = "lco_" + Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);
        String keyHash = sha256(rawKey);
        String prefix = rawKey.substring(4, 12);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO api_keys (user_id, key_hash, key_prefix, tier, mode) VALUES (?, ?, ?, ?, 'online')")) {
            ps.setString(1, userId.toString());
            ps.setString(2, keyHash);
            ps.setString(3, prefix);
            ps.setString(4, tier);
            ps.executeUpdate();
        }
        return rawKey;
    }

    public List<KeyInfo> listUserKeys(UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, key_prefix, tier, mode, created_at, revoked_at, expires_at " +
                 "FROM api_keys WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC")) {
            ps.setString(1, userId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<KeyInfo> keys = new ArrayList<>();
                while (rs.next()) {
                    Timestamp expiresAtTs = rs.getTimestamp(7);
                    Long expiresAt = expiresAtTs != null ? expiresAtTs.toInstant().getEpochSecond() : null;
                    keys.add(new KeyInfo(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) != null ? rs.getTimestamp(5).toInstant() : null,
                        rs.getTimestamp(6) != null ? rs.getTimestamp(6).toInstant() : null,
                        null, null, expiresAt));
                }
                return keys;
            }
        }
    }

    public boolean revokeKey(UUID keyId, UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE api_keys SET revoked_at = NOW() WHERE id = ? AND user_id = ? AND revoked_at IS NULL")) {
            ps.setString(1, keyId.toString());
            ps.setString(2, userId.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public KeyInfo findActiveKeyByHash(String rawKey) throws Exception {
        String hash = sha256(rawKey);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT k.id, k.key_prefix, k.tier, k.mode, k.created_at, k.revoked_at, " +
                 "       k.user_id, u.email, k.expires_at " +
                 "FROM api_keys k JOIN users u ON k.user_id = u.id " +
                 "WHERE k.key_hash = ? AND k.revoked_at IS NULL")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp expiresAtTs = rs.getTimestamp(9);
                Long expiresAt = expiresAtTs != null ? expiresAtTs.toInstant().getEpochSecond() : null;
                return new KeyInfo(
                    UUID.fromString(rs.getString(1)),
                    rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getTimestamp(5) != null ? rs.getTimestamp(5).toInstant() : null,
                    rs.getTimestamp(6) != null ? rs.getTimestamp(6).toInstant() : null,
                    UUID.fromString(rs.getString(7)), rs.getString(8), expiresAt);
            }
        }
    }

    static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    public record KeyInfo(UUID id, String prefix, String tier, String mode,
                          Instant createdAt, Instant revokedAt, UUID userId, String userEmail,
                          Long expiresAt) {
        public KeyInfo(UUID id, String prefix, String tier, String mode,
                       Instant createdAt, Instant revokedAt) {
            this(id, prefix, tier, mode, createdAt, revokedAt, null, null, null);
        }
    }
}
