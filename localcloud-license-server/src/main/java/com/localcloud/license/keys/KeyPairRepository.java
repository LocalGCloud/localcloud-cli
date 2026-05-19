package com.localcloud.license.keys;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class KeyPairRepository {

    private final DataSource ds;

    public KeyPairRepository(DataSource ds) {
        this.ds = ds;
    }

    public record KeyPairRow(UUID id, String keyType, String algorithm,
                             String privateKey, String publicKey,
                             String kid, String status, long createdAt, Long rotatedAt) {}

    public Optional<KeyPairRow> getActiveKey(String keyType) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, key_type, algorithm, private_key, public_key, kid, status, " +
                 "EXTRACT(EPOCH FROM created_at)::bigint AS created_at, " +
                 "EXTRACT(EPOCH FROM rotated_at)::bigint AS rotated_at " +
                 "FROM key_pairs WHERE key_type = ? AND status = 'active' ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, keyType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(row(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get active key for " + keyType, e);
        }
        return Optional.empty();
    }

    public void insertKey(KeyPairRow row) {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                         "UPDATE key_pairs SET status = 'previous', rotated_at = NOW() " +
                         "WHERE key_type = ? AND status = 'active'")) {
                    ps.setString(1, row.keyType);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO key_pairs (key_type, algorithm, private_key, public_key, kid, status) " +
                         "VALUES (?, ?, ?, ?, ?, 'active')")) {
                    ps.setString(1, row.keyType);
                    ps.setString(2, row.algorithm);
                    ps.setString(3, row.privateKey);
                    ps.setString(4, row.publicKey);
                    ps.setString(5, row.kid);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert key for " + row.keyType, e);
        }
    }

    public List<KeyPairRow> listAll() {
        List<KeyPairRow> rows = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, key_type, algorithm, private_key, public_key, kid, status, " +
                 "EXTRACT(EPOCH FROM created_at)::bigint AS created_at, " +
                 "EXTRACT(EPOCH FROM rotated_at)::bigint AS rotated_at " +
                 "FROM key_pairs ORDER BY key_type, created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) rows.add(row(rs));
        } catch (Exception e) {
            throw new RuntimeException("Failed to list key pairs", e);
        }
        return rows;
    }

    public boolean hasAnyKeys() {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM key_pairs LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        } catch (Exception e) {
            throw new RuntimeException("Failed to check key pairs", e);
        }
    }

    private KeyPairRow row(ResultSet rs) throws SQLException {
        long rotated = rs.getLong("rotated_at");
        return new KeyPairRow(
            UUID.fromString(rs.getString("id")),
            rs.getString("key_type"),
            rs.getString("algorithm"),
            rs.getString("private_key"),
            rs.getString("public_key"),
            rs.getString("kid"),
            rs.getString("status"),
            rs.getLong("created_at"),
            rs.wasNull() ? null : rotated
        );
    }
}
