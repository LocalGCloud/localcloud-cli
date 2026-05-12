package com.localcloud.license.validation;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import com.localcloud.license.keys.ApiKeyRepository;
import com.localcloud.license.trial.TrialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LicenseValidatorExpiryTest {

    private DataSource ds;
    private AuthRepository authRepo;
    private ApiKeyRepository keyRepo;
    private TrialRepository trialRepo;
    private LicenseValidator validator;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:expiry_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.authRepo = new AuthRepository(ds);
        this.keyRepo = new ApiKeyRepository(ds);
        var deviceTracker = new DeviceTracker(ds);
        this.trialRepo = new TrialRepository(ds, 14);
        this.validator = new LicenseValidator(keyRepo, authRepo, deviceTracker, trialRepo);
        this.userId = authRepo.createUser("expiry@example.com");
        authRepo.markEmailVerified("expiry@example.com");
    }

    /**
     * Trial key whose trial record has an expiry in the past → rejected.
     */
    @Test
    void expiredTrial_returnsInvalid() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "trial");

        // Insert a trial record with expires_at in the past
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO trials (user_id, device_fingerprint, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, userId.toString());
            ps.setString(2, "device-expired-trial");
            ps.setTimestamp(3, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
            ps.executeUpdate();
        }

        var result = validator.validate(rawKey, "device-expired-trial");
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Trial expired"),
            "Expected 'Trial expired' in message but got: " + result.errorMessage());
    }

    /**
     * Trial key whose trial record has an expiry in the future → valid.
     */
    @Test
    void activeTrial_returnsValid() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "trial");

        // Insert a trial record with expires_at in the future
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO trials (user_id, device_fingerprint, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, userId.toString());
            ps.setString(2, "device-active-trial");
            ps.setTimestamp(3, Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
            ps.executeUpdate();
        }

        var result = validator.validate(rawKey, "device-active-trial");
        assertTrue(result.valid());
        assertEquals("trial", result.tier());
    }

    /**
     * Trial key with no trial record at all → treated as expired.
     */
    @Test
    void trialKeyNoTrialRecord_returnsInvalid() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "trial");
        // No trial record inserted

        var result = validator.validate(rawKey, "device-no-record");
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Trial expired"),
            "Expected 'Trial expired' in message but got: " + result.errorMessage());
    }

    /**
     * Subscription key with expires_at in the past → rejected.
     */
    @Test
    void expiredSubscriptionKey_returnsInvalid() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");

        // Set expires_at to yesterday on the key row
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE api_keys SET expires_at = ? WHERE user_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
            ps.setString(2, userId.toString());
            ps.executeUpdate();
        }

        var result = validator.validate(rawKey, "device-sub-expired");
        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("License expired"),
            "Expected 'License expired' in message but got: " + result.errorMessage());
    }

    /**
     * Subscription key with expires_at = NULL (perpetual) → valid.
     */
    @Test
    void activeSubscriptionKey_perpetual_returnsValid() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        // expires_at is NULL by default (perpetual)

        var result = validator.validate(rawKey, "device-perpetual");
        assertTrue(result.valid());
        assertEquals("pro", result.tier());
    }

    /**
     * PRO key with no trial record — must not throw NPE from trialRepo lookup.
     */
    @Test
    void nonTrialKey_noExpiryCheck() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        // No trial record exists; trialRepo.getTrialInfo should never be called for non-trial tier

        var result = validator.validate(rawKey, "device-pro");
        assertTrue(result.valid(), "PRO key without trial record should be valid");
        assertEquals("pro", result.tier());
    }
}
