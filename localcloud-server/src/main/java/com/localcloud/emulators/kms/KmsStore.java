package com.localcloud.emulators.kms;

import com.localcloud.persistence.PostgresDataSource;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL-backed metadata and local key-material store for Cloud KMS.
 */
public class KmsStore {

    private static final SecureRandom RNG = new SecureRandom();

    private final PostgresDataSource dataSource;

    public KmsStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void createKeyRing(String project, String location, String keyRing) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO kms_key_rings (project_id, location_id, key_ring_id) VALUES (?, ?, ?)")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, keyRing);
            ps.executeUpdate();
        }
    }

    public Map<String, Object> getKeyRing(String project, String location, String keyRing) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, key_ring_id, created_at FROM kms_key_rings " +
                             "WHERE project_id = ? AND location_id = ? AND key_ring_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, keyRing);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? keyRingMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listKeyRings(String project, String location) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, key_ring_id, created_at FROM kms_key_rings " +
                             "WHERE project_id = ? AND location_id = ? ORDER BY key_ring_id")) {
            ps.setString(1, project);
            ps.setString(2, location);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(keyRingMap(rs));
                }
            }
        }
        return result;
    }

    public void createCryptoKey(String project, String location, String keyRing, String cryptoKey,
                                String purpose, String algorithm, String labelsJson) throws SQLException {
        byte[] keyMaterial = new byte[32];
        RNG.nextBytes(keyMaterial);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement keyPs = conn.prepareStatement(
                    "INSERT INTO kms_crypto_keys (project_id, location_id, key_ring_id, crypto_key_id, purpose, algorithm, primary_version, labels) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 1, ?)");
                 PreparedStatement versionPs = conn.prepareStatement(
                    "INSERT INTO kms_crypto_key_versions (project_id, location_id, key_ring_id, crypto_key_id, version_number, state, algorithm, key_material) " +
                            "VALUES (?, ?, ?, ?, 1, 'ENABLED', ?, ?)")) {
                keyPs.setString(1, project);
                keyPs.setString(2, location);
                keyPs.setString(3, keyRing);
                keyPs.setString(4, cryptoKey);
                keyPs.setString(5, purpose);
                keyPs.setString(6, algorithm);
                keyPs.setString(7, labelsJson != null ? labelsJson : "{}");
                keyPs.executeUpdate();

                versionPs.setString(1, project);
                versionPs.setString(2, location);
                versionPs.setString(3, keyRing);
                versionPs.setString(4, cryptoKey);
                versionPs.setString(5, algorithm);
                versionPs.setBytes(6, keyMaterial);
                versionPs.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public Map<String, Object> getCryptoKey(String project, String location, String keyRing, String cryptoKey) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, key_ring_id, crypto_key_id, purpose, algorithm, primary_version, labels, created_at " +
                             "FROM kms_crypto_keys WHERE project_id = ? AND location_id = ? AND key_ring_id = ? AND crypto_key_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, keyRing);
            ps.setString(4, cryptoKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? cryptoKeyMap(rs) : null;
            }
        }
    }

    public List<Map<String, Object>> listCryptoKeys(String project, String location, String keyRing) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, key_ring_id, crypto_key_id, purpose, algorithm, primary_version, labels, created_at " +
                             "FROM kms_crypto_keys WHERE project_id = ? AND location_id = ? AND key_ring_id = ? ORDER BY crypto_key_id")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, keyRing);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(cryptoKeyMap(rs));
                }
            }
        }
        return result;
    }

    public Map<String, Object> getPrimaryVersion(String project, String location, String keyRing, String cryptoKey) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT v.project_id, v.location_id, v.key_ring_id, v.crypto_key_id, v.version_number, v.state, v.algorithm, v.key_material, v.created_at " +
                             "FROM kms_crypto_key_versions v JOIN kms_crypto_keys k ON " +
                             "v.project_id = k.project_id AND v.location_id = k.location_id AND v.key_ring_id = k.key_ring_id " +
                             "AND v.crypto_key_id = k.crypto_key_id AND v.version_number = k.primary_version " +
                             "WHERE v.project_id = ? AND v.location_id = ? AND v.key_ring_id = ? AND v.crypto_key_id = ?")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, keyRing);
            ps.setString(4, cryptoKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? versionMap(rs, true) : null;
            }
        }
    }

    public Map<String, Object> getVersion(String project, String location, String keyRing, String cryptoKey, int version) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, key_ring_id, crypto_key_id, version_number, state, algorithm, key_material, created_at " +
                             "FROM kms_crypto_key_versions WHERE project_id = ? AND location_id = ? AND key_ring_id = ? AND crypto_key_id = ? AND version_number = ?")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, keyRing);
            ps.setString(4, cryptoKey);
            ps.setInt(5, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? versionMap(rs, true) : null;
            }
        }
    }

    public List<Map<String, Object>> listVersions(String project, String location, String keyRing, String cryptoKey) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, key_ring_id, crypto_key_id, version_number, state, algorithm, key_material, created_at " +
                             "FROM kms_crypto_key_versions WHERE project_id = ? AND location_id = ? AND key_ring_id = ? AND crypto_key_id = ? ORDER BY version_number")) {
            ps.setString(1, project);
            ps.setString(2, location);
            ps.setString(3, keyRing);
            ps.setString(4, cryptoKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(versionMap(rs, false));
                }
            }
        }
        return result;
    }

    public boolean setPrimaryVersion(String project, String location, String keyRing, String cryptoKey, int version) throws SQLException {
        // Atomic check-and-update: only succeeds if the version exists in kms_crypto_key_versions.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE kms_crypto_keys SET primary_version = ? " +
                     "WHERE project_id = ? AND location_id = ? AND key_ring_id = ? AND crypto_key_id = ? " +
                     "AND EXISTS (SELECT 1 FROM kms_crypto_key_versions " +
                     "WHERE project_id = ? AND location_id = ? AND key_ring_id = ? AND crypto_key_id = ? AND version_number = ?)")) {
            ps.setInt(1, version);
            ps.setString(2, project);
            ps.setString(3, location);
            ps.setString(4, keyRing);
            ps.setString(5, cryptoKey);
            ps.setString(6, project);
            ps.setString(7, location);
            ps.setString(8, keyRing);
            ps.setString(9, cryptoKey);
            ps.setInt(10, version);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateVersionState(String project, String location, String keyRing, String cryptoKey,
                                      int version, String state) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE kms_crypto_key_versions SET state = ? WHERE project_id = ? AND location_id = ? AND key_ring_id = ? AND crypto_key_id = ? AND version_number = ?")) {
            ps.setString(1, state);
            ps.setString(2, project);
            ps.setString(3, location);
            ps.setString(4, keyRing);
            ps.setString(5, cryptoKey);
            ps.setInt(6, version);
            return ps.executeUpdate() > 0;
        }
    }

    public void clearAll() {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM kms_crypto_key_versions");
            stmt.execute("DELETE FROM kms_crypto_keys");
            stmt.execute("DELETE FROM kms_key_rings");
        } catch (SQLException ignored) {
        }
    }

    private Map<String, Object> keyRingMap(ResultSet rs) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        result.put("project_id", rs.getString("project_id"));
        result.put("location_id", rs.getString("location_id"));
        result.put("key_ring_id", rs.getString("key_ring_id"));
        result.put("created_at", rs.getTimestamp("created_at"));
        return result;
    }

    private Map<String, Object> cryptoKeyMap(ResultSet rs) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        result.put("project_id", rs.getString("project_id"));
        result.put("location_id", rs.getString("location_id"));
        result.put("key_ring_id", rs.getString("key_ring_id"));
        result.put("crypto_key_id", rs.getString("crypto_key_id"));
        result.put("purpose", rs.getString("purpose"));
        result.put("algorithm", rs.getString("algorithm"));
        result.put("primary_version", rs.getInt("primary_version"));
        result.put("labels", rs.getString("labels"));
        result.put("created_at", rs.getTimestamp("created_at"));
        return result;
    }

    private Map<String, Object> versionMap(ResultSet rs, boolean includeKeyMaterial) throws SQLException {
        Map<String, Object> result = new HashMap<>();
        result.put("project_id", rs.getString("project_id"));
        result.put("location_id", rs.getString("location_id"));
        result.put("key_ring_id", rs.getString("key_ring_id"));
        result.put("crypto_key_id", rs.getString("crypto_key_id"));
        result.put("version_number", rs.getInt("version_number"));
        result.put("state", rs.getString("state"));
        result.put("algorithm", rs.getString("algorithm"));
        result.put("created_at", rs.getTimestamp("created_at"));
        if (includeKeyMaterial) {
            result.put("key_material", rs.getBytes("key_material"));
        }
        return result;
    }
}
