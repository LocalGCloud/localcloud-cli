package com.localcloud.license.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.keys.ApiKeyRepository;
import com.localcloud.license.keys.KeyPairRepository;
import com.localcloud.license.trial.TrialRepository;
import com.localcloud.license.validation.KeyPairManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ProducesJson
public class AdminHandler {

    private static final Logger logger = LoggerFactory.getLogger(AdminHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AdminSessionStore sessionStore;
    private final AdminStatsRepository statsRepo;
    private final DataSource ds;
    private final String adminPassword;
    private volatile PrivateKey offlineSigningKey;
    private volatile PublicKey offlinePublicKey;
    private volatile boolean offlineKeysEnabled;
    private final KeyPairRepository keyPairRepo;
    private final KeyPairManager keyPairManager;

    public AdminHandler(AdminSessionStore sessionStore, AdminStatsRepository statsRepo,
                        DataSource dataSource, String adminPassword,
                        PrivateKey offlineSigningKey, PublicKey offlinePublicKey,
                        KeyPairRepository keyPairRepo, KeyPairManager keyPairManager) {
        this.sessionStore = sessionStore;
        this.statsRepo = statsRepo;
        this.ds = dataSource;
        this.adminPassword = adminPassword;
        this.offlineSigningKey = offlineSigningKey;
        this.offlinePublicKey = offlinePublicKey;
        this.offlineKeysEnabled = offlineSigningKey != null;
        this.keyPairRepo = keyPairRepo;
        this.keyPairManager = keyPairManager;
    }

    // === Auth ===

    @Post("/login")
    public HttpResponse login(@RequestObject Map<String, String> body) {
        String password = body.get("password");
        if (password == null || !password.equals(adminPassword)) {
            return error(HttpStatus.UNAUTHORIZED, "Invalid admin password");
        }
        String token = sessionStore.createSession();
        return ok(Map.of("token", token, "expires_in_seconds", 28800));
    }

    @Post("/logout")
    public HttpResponse logout(@Header String authorization) {
        String token = extractToken(authorization);
        if (token != null) sessionStore.removeSession(token);
        return ok(Map.of("message", "Logged out"));
    }

    // === Dashboard ===

    @Get("/stats")
    public HttpResponse stats() {
        try {
            return ok(statsRepo.getStats());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // === Tier Info ===

    @Get("/tiers")
    public HttpResponse listTiers() {
        return ok(List.of(
            tier("community", "Free", "Basic GCP services: Cloud Storage, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Logging, Monitoring, Memorystore, Workflows. Suitable for personal projects and development."),
            tier("pro", "Pro", "All community services plus Bigtable, Spanner, GKE, Compute Engine, Cloud Run, Vertex AI, Cloud KMS, Cloud SQL. Suitable for production workloads and teams."),
            tier("trial", "14-Day Trial", "Full pro access for 14 days. No credit card required. Expires automatically after the trial period.")
        ));
    }

    private Map<String, Object> tier(String id, String name, String description) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("name", name);
        t.put("description", description);
        return t;
    }

    // === Users ===

    @Get("/users")
    public HttpResponse listUsers(@Param("q") Optional<String> query) {
        try (Connection conn = ds.getConnection()) {
            String sql = "SELECT u.id, u.email, u.email_verified, u.created_at, u.status, " +
                "(SELECT COUNT(*) FROM api_keys k WHERE k.user_id = u.id) AS key_count, " +
                "(SELECT COUNT(*) FROM trials t WHERE t.user_id = u.id) AS trial_count, " +
                "(SELECT COUNT(*) FROM devices d WHERE d.user_id = u.id) AS device_count " +
                "FROM users u";
            if (query.isPresent() && !query.get().isBlank()) {
                sql += " WHERE u.email ILIKE ?";
            }
            sql += " ORDER BY u.created_at DESC LIMIT 200";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (query.isPresent() && !query.get().isBlank()) {
                    ps.setString(1, "%" + query.get() + "%");
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> users = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> u = new LinkedHashMap<>();
                        u.put("id", rs.getString("id"));
                        u.put("email", rs.getString("email"));
                        u.put("email_verified", rs.getBoolean("email_verified"));
                        u.put("created_at", rs.getTimestamp("created_at").getTime() / 1000);
                        u.put("status", rs.getString("status"));
                        u.put("key_count", rs.getLong("key_count"));
                        u.put("trial_count", rs.getLong("trial_count"));
                        u.put("device_count", rs.getLong("device_count"));
                        users.add(u);
                    }
                    return ok(users);
                }
            }
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Get("/users/{userId}")
    public HttpResponse getUser(@Param String userId) {
        try {
            UUID parsedId;
            try {
                parsedId = UUID.fromString(userId);
            } catch (IllegalArgumentException e) {
                return error(HttpStatus.BAD_REQUEST, "Invalid user_id format — must be a valid UUID");
            }
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, email, email_verified, created_at, status FROM users WHERE id = ?")) {
                ps.setObject(1, parsedId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return error(HttpStatus.NOT_FOUND, "User not found");
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("id", rs.getString("id"));
                    user.put("email", rs.getString("email"));
                    user.put("email_verified", rs.getBoolean("email_verified"));
                    user.put("created_at", rs.getTimestamp("created_at").getTime() / 1000);
                    user.put("status", rs.getString("status"));

                    user.put("keys", getUserKeys(parsedId));
                    user.put("devices", getUserDevices(parsedId));
                    user.put("trials", getUserTrials(parsedId));
                    return ok(user);
                }
            }
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // === Keys ===

    @Get("/keys")
    public HttpResponse listKeys(@Param("q") Optional<String> query,
                                 @Param("tier") Optional<String> tier,
                                 @Param("status") Optional<String> status) {
        try (Connection conn = ds.getConnection()) {
            StringBuilder sql = new StringBuilder(
                "SELECT k.id, k.key_prefix, k.tier, k.mode, k.created_at, k.revoked_at, k.expires_at, " +
                "k.user_id, u.email FROM api_keys k JOIN users u ON k.user_id = u.id WHERE 1=1");
            if (query.isPresent() && !query.get().isBlank()) {
                sql.append(" AND (k.key_prefix ILIKE ? OR u.email ILIKE ?)");
            }
            if (tier.isPresent() && !tier.get().isBlank()) {
                sql.append(" AND k.tier = ?");
            }
            if (status.isPresent() && !status.get().isBlank()) {
                if ("active".equals(status.get())) sql.append(" AND k.revoked_at IS NULL");
                else if ("revoked".equals(status.get())) sql.append(" AND k.revoked_at IS NOT NULL");
                else if ("expired".equals(status.get())) sql.append(" AND k.expires_at IS NOT NULL AND k.expires_at < NOW()");
            }
            sql.append(" ORDER BY k.created_at DESC LIMIT 500");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                if (query.isPresent() && !query.get().isBlank()) {
                    String q = "%" + query.get() + "%";
                    ps.setString(idx++, q);
                    ps.setString(idx++, q);
                }
                if (tier.isPresent() && !tier.get().isBlank()) {
                    ps.setString(idx++, tier.get());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> keys = new ArrayList<>();
                    while (rs.next()) {
                        keys.add(keyRow(rs));
                    }
                    return ok(keys);
                }
            }
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Get("/keys/{keyId}")
    public HttpResponse getKey(@Param String keyId) {
        try {
            UUID parsedId;
            try {
                parsedId = UUID.fromString(keyId);
            } catch (IllegalArgumentException e) {
                return error(HttpStatus.BAD_REQUEST, "Invalid key id format — must be a valid UUID");
            }
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT k.id, k.key_prefix, k.tier, k.mode, k.created_at, k.revoked_at, k.expires_at, " +
                     "k.user_id, u.email FROM api_keys k JOIN users u ON k.user_id = u.id WHERE k.id = ?")) {
                ps.setObject(1, parsedId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return error(HttpStatus.NOT_FOUND, "Key not found");
                    Map<String, Object> key = keyRow(rs);
                    key.put("devices", getKeyDevices(parsedId));
                    return ok(key);
                }
            }
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/keys")
    public HttpResponse generateKey(@RequestObject Map<String, String> body) {
        try {
            String email = body.get("email");
            String tier = body.getOrDefault("tier", "pro");
            String mode = body.getOrDefault("mode", "online");
            String deviceId = body.getOrDefault("device_id", "");

            if (email == null || email.isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "email is required");
            }
            email = email.toLowerCase().trim();
            if ("offline".equals(mode)) {
                if (offlineSigningKey == null) {
                    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Offline key generation not available — no offline signing key configured. Generate one in Signing Keys > Offline.");
                }
            } else {
                if (!keyPairManager.hasKey()) {
                    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Online key generation not available — no online signing key configured. Generate one in Signing Keys > Online.");
                }
            }

            AuthRepository authRepo = new AuthRepository(ds);
            var result = authRepo.createUser(email);
            UUID userId = result.userId();
            boolean userCreated = result.created();
            ApiKeyRepository keyRepo = new ApiKeyRepository(ds);

            String rawKey;
            long expiresAt = 0;
            if ("offline".equals(mode)) {
                rawKey = generateOfflineKey(offlineSigningKey, email, tier, deviceId, 365);
                expiresAt = Instant.now().plus(365, ChronoUnit.DAYS).getEpochSecond();
                keyRepo.insertOfflineKey(userId, tier, rawKey, expiresAt);
            } else {
                rawKey = keyRepo.generateOnlineKey(userId, tier);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("key", rawKey);
            response.put("tier", tier);
            response.put("mode", mode);
            response.put("user_id", userId.toString());
            response.put("email", email);
            response.put("user_created", userCreated);
            response.put("message", userCreated ? "User created and key generated" : "Key generated for existing user");
            return ok(response);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private String generateOfflineKey(PrivateKey privateKey, String email, String tier,
                                       String deviceId, int days) throws Exception {
        Instant now = Instant.now();
        long nowSec = now.getEpochSecond();
        long expires = now.plus(days, ChronoUnit.DAYS).getEpochSecond();

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("email", email);
            claims.put("tier", tier);
            claims.put("issued", nowSec);
            claims.put("expires", expires);
        claims.put("offline", true);
        if (offlinePublicKey != null) {
            String pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(offlinePublicKey.getEncoded());
            int prefixLen = ("Ed25519".equals(offlinePublicKey.getAlgorithm()) || "EdDSA".equals(offlinePublicKey.getAlgorithm())) ? 16 : 0;
            if (pubB64.length() > prefixLen + 8) {
                claims.put("kid", pubB64.substring(prefixLen, prefixLen + 8));
            } else if (pubB64.length() >= 8) {
                claims.put("kid", pubB64.substring(0, 8));
            }
        }
        if (deviceId != null && !deviceId.isBlank()) {
            claims.put("device_id", deviceId);
        }

        byte[] payloadBytes = mapper.writeValueAsBytes(claims);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payloadBytes);
        byte[] signature = sig.sign();

        byte[] combined = new byte[1 + payloadBytes.length + signature.length];
        combined[0] = 0x01;
        System.arraycopy(payloadBytes, 0, combined, 1, payloadBytes.length);
        System.arraycopy(signature, 0, combined, 1 + payloadBytes.length, signature.length);

        return "lc_of_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    @Post("/keys/{keyId}/revoke")
    public HttpResponse revokeKey(@Param String keyId) {
        try {
            UUID parsedId;
            try {
                parsedId = UUID.fromString(keyId);
            } catch (IllegalArgumentException e) {
                return error(HttpStatus.BAD_REQUEST, "Invalid key id format — must be a valid UUID");
            }
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE api_keys SET revoked_at = NOW() WHERE id = ? AND revoked_at IS NULL")) {
                ps.setObject(1, parsedId);
                if (ps.executeUpdate() == 0) {
                    return error(HttpStatus.NOT_FOUND, "Key not found or already revoked");
                }
                return ok(Map.of("message", "Key revoked"));
            }
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // === Signing Key Pairs ===

    @Get("/key-pairs")
    public HttpResponse listKeyPairs() {
        try {
            var keys = keyPairRepo.listAll();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var k : keys) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", k.id().toString());
                m.put("key_type", k.keyType());
                m.put("algorithm", k.algorithm());
                m.put("public_key", k.publicKey());
                m.put("kid", k.kid());
                m.put("private_key", k.privateKey());
                m.put("status", k.status());
                m.put("created_at", k.createdAt());
                m.put("rotated_at", k.rotatedAt());
                result.add(m);
            }
            return ok(result);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/key-pairs/generate-online")
    public HttpResponse generateOnlineKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair kp = gen.generateKeyPair();

        String privB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded());
        String pubB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPublic().getEncoded());

        keyPairRepo.insertKey(new KeyPairRepository.KeyPairRow(
            null, "online", "RSA-2048", privB64, pubB64, null, null, 0, null));

        keyPairManager.setKeyPair(kp);
        logger.info("Generated and activated new online signing key pair");

        return ok(Map.of("message", "Online signing key pair generated and activated",
                         "algorithm", "RSA-2048", "public_key", pubB64));
    }

    @Post("/key-pairs/generate-offline")
    public HttpResponse generateOfflineKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = gen.generateKeyPair();

        byte[] pubEncoded = kp.getPublic().getEncoded();
        String privB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPrivate().getEncoded());
        String pubB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubEncoded);

        // kid = first 8 chars after the DER prefix (12 bytes = 16 base64 chars)
        String kid = null;
        String pubB64Trimmed = Base64.getUrlEncoder().withoutPadding().encodeToString(pubEncoded);
        int prefixLen = 16;
        if (pubB64Trimmed.length() > prefixLen + 8) {
            kid = pubB64Trimmed.substring(prefixLen, prefixLen + 8);
        }

        keyPairRepo.insertKey(new KeyPairRepository.KeyPairRow(
            null, "offline", "Ed25519", privB64, pubB64, kid, null, 0, null));

        this.offlineSigningKey = kp.getPrivate();
        this.offlinePublicKey = kp.getPublic();
        this.offlineKeysEnabled = true;
        logger.info("Generated and activated new offline signing key pair");

        return ok(Map.of("message", "Offline signing key pair generated and activated",
                         "algorithm", "Ed25519", "public_key", pubB64, "kid", kid));
    }

    // === Trials ===

    @Get("/trials")
    public HttpResponse listTrials(@Param("status") Optional<String> status) {
        try (Connection conn = ds.getConnection()) {
            StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.user_id, u.email, t.device_fingerprint, t.started_at, t.expires_at " +
                "FROM trials t JOIN users u ON t.user_id = u.id WHERE 1=1");
            if (status.isPresent() && "active".equals(status.get())) {
                sql.append(" AND t.expires_at > NOW()");
            } else if (status.isPresent() && "expired".equals(status.get())) {
                sql.append(" AND t.expires_at <= NOW()");
            }
            sql.append(" ORDER BY t.started_at DESC LIMIT 500");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString());
                 ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> trials = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", rs.getString("id"));
                    t.put("user_id", rs.getString("user_id"));
                    t.put("email", rs.getString("email"));
                    t.put("device_fingerprint", rs.getString("device_fingerprint"));
                    t.put("started_at", rs.getTimestamp("started_at").getTime() / 1000);
                    t.put("expires_at", rs.getTimestamp("expires_at").getTime() / 1000);
                    t.put("active", rs.getTimestamp("expires_at").getTime() > System.currentTimeMillis());
                    trials.add(t);
                }
                return ok(trials);
            }
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // === Devices ===

    @Get("/devices")
    public HttpResponse listDevices(@Param("q") Optional<String> query) {
        try (Connection conn = ds.getConnection()) {
            String sql = "SELECT d.id, d.user_id, u.email, d.device_fingerprint, d.first_seen, d.last_seen " +
                "FROM devices d JOIN users u ON d.user_id = u.id";
            if (query.isPresent() && !query.get().isBlank()) {
                sql += " WHERE d.device_fingerprint ILIKE ? OR u.email ILIKE ?";
            }
            sql += " ORDER BY d.last_seen DESC LIMIT 500";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (query.isPresent() && !query.get().isBlank()) {
                    String q = "%" + query.get() + "%";
                    ps.setString(idx++, q);
                    ps.setString(idx++, q);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> devices = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("id", rs.getString("id"));
                        d.put("user_id", rs.getString("user_id"));
                        d.put("email", rs.getString("email"));
                        d.put("device_fingerprint", rs.getString("device_fingerprint"));
                        d.put("first_seen", rs.getTimestamp("first_seen").getTime() / 1000);
                        d.put("last_seen", rs.getTimestamp("last_seen").getTime() / 1000);
                        devices.add(d);
                    }
                    return ok(devices);
                }
            }
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // === Health ===

    @Get("/health")
    public HttpResponse health() {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {
            boolean dbOk = rs.next();
            return ok(Map.of(
                "status", dbOk ? "ok" : "degraded",
                "database", dbOk ? "connected" : "unreachable",
                "offline_keys_enabled", offlineKeysEnabled,
                "online_key_configured", keyPairManager.hasKey()
            ));
        } catch (Exception e) {
            return ok(Map.of("status", "degraded", "database", "unreachable", "error", e.getMessage(),
                "offline_keys_enabled", offlineKeysEnabled, "online_key_configured", keyPairManager.hasKey()));
        }
    }

    // === Helpers ===

    private Map<String, Object> keyRow(ResultSet rs) throws Exception {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("id", rs.getString("id"));
        k.put("key_prefix", rs.getString("key_prefix"));
        k.put("tier", rs.getString("tier"));
        k.put("mode", rs.getString("mode"));
        k.put("created_at", rs.getTimestamp("created_at").getTime() / 1000);
        Timestamp revoked = rs.getTimestamp("revoked_at");
        k.put("revoked_at", revoked != null ? revoked.getTime() / 1000 : null);
        Timestamp expires = rs.getTimestamp("expires_at");
        k.put("expires_at", expires != null ? expires.getTime() / 1000 : null);
        k.put("user_id", rs.getString("user_id"));
        k.put("email", rs.getString("email"));
        return k;
    }

    private List<Map<String, Object>> getUserKeys(UUID userId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, key_prefix, tier, mode, created_at, revoked_at, expires_at " +
                 "FROM api_keys WHERE user_id = ? ORDER BY created_at DESC")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> keys = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> k = new LinkedHashMap<>();
                    k.put("id", rs.getString("id"));
                    k.put("key_prefix", rs.getString("key_prefix"));
                    k.put("tier", rs.getString("tier"));
                    k.put("mode", rs.getString("mode"));
                    k.put("created_at", rs.getTimestamp("created_at").getTime() / 1000);
                    Timestamp revoked = rs.getTimestamp("revoked_at");
                    k.put("revoked_at", revoked != null ? revoked.getTime() / 1000 : null);
                    Timestamp expires = rs.getTimestamp("expires_at");
                    k.put("expires_at", expires != null ? expires.getTime() / 1000 : null);
                    keys.add(k);
                }
                return keys;
            }
        }
    }

    private List<Map<String, Object>> getUserDevices(UUID userId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, device_fingerprint, first_seen, last_seen FROM devices WHERE user_id = ? ORDER BY last_seen DESC")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> devices = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", rs.getString("id"));
                    d.put("device_fingerprint", rs.getString("device_fingerprint"));
                    d.put("first_seen", rs.getTimestamp("first_seen").getTime() / 1000);
                    d.put("last_seen", rs.getTimestamp("last_seen").getTime() / 1000);
                    devices.add(d);
                }
                return devices;
            }
        }
    }

    private List<Map<String, Object>> getUserTrials(UUID userId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, device_fingerprint, started_at, expires_at FROM trials WHERE user_id = ? ORDER BY started_at DESC")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> trials = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", rs.getString("id"));
                    t.put("device_fingerprint", rs.getString("device_fingerprint"));
                    t.put("started_at", rs.getTimestamp("started_at").getTime() / 1000);
                    t.put("expires_at", rs.getTimestamp("expires_at").getTime() / 1000);
                    trials.add(t);
                }
                return trials;
            }
        }
    }

    private List<Map<String, Object>> getKeyDevices(UUID keyId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT d.id, d.device_fingerprint, d.first_seen, d.last_seen, u.email " +
                 "FROM devices d JOIN api_keys k ON d.user_id = k.user_id " +
                 "JOIN users u ON d.user_id = u.id WHERE k.id = ? ORDER BY d.last_seen DESC LIMIT 50")) {
            ps.setObject(1, keyId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> devices = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", rs.getString("id"));
                    d.put("device_fingerprint", rs.getString("device_fingerprint"));
                    d.put("first_seen", rs.getTimestamp("first_seen").getTime() / 1000);
                    d.put("last_seen", rs.getTimestamp("last_seen").getTime() / 1000);
                    devices.add(d);
                }
                return devices;
            }
        }
    }

    private String extractToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String t = authorization.substring(7).strip();
            return t.startsWith("adm_") ? t : null;
        }
        return null;
    }

    private HttpResponse ok(Object body) {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, mapper.writeValueAsString(body));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private HttpResponse error(HttpStatus status, String message) {
        try {
            return HttpResponse.of(status, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) {
            return HttpResponse.of(status);
        }
    }
}
