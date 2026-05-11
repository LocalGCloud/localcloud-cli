package com.localcloud.license.validation;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import com.localcloud.license.keys.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LicenseValidationTest {

    private DataSource ds;
    private LicenseValidator validator;
    private UUID userId;
    private String activeKey;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:validate_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa"); h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        var authRepo = new AuthRepository(ds);
        var keyRepo = new ApiKeyRepository(ds);
        var deviceTracker = new DeviceTracker(ds);
        this.validator = new LicenseValidator(keyRepo, authRepo, deviceTracker);
        this.userId = authRepo.createUser("validator@example.com");
        authRepo.markEmailVerified("validator@example.com");
        this.activeKey = keyRepo.generateOnlineKey(userId, "pro");
    }

    @Test
    void validKeyReturnsProTier() throws Exception {
        var result = validator.validate(activeKey, "device-abc");
        assertTrue(result.valid());
        assertEquals("pro", result.tier());
        assertEquals("validator@example.com", result.email());
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        var result = validator.validate("lco_unknownkey", "device-abc");
        assertFalse(result.valid());
    }

    @Test
    void deviceIsTrackedOnValidation() throws Exception {
        validator.validate(activeKey, "device-xyz789");
        var result = validator.validate(activeKey, "device-xyz789");
        assertTrue(result.valid());
    }

    @Test
    void revokedKeyIsRejected() throws Exception {
        var keyRepo = new ApiKeyRepository(ds);
        var keys = keyRepo.listUserKeys(userId);
        keyRepo.revokeKey(keys.get(0).id(), userId);
        var result = validator.validate(activeKey, "device-abc");
        assertFalse(result.valid());
    }
}
