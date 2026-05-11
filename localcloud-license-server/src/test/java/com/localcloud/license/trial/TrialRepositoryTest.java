package com.localcloud.license.trial;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrialRepositoryTest {

    private DataSource ds;
    private TrialRepository trialRepo;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:trial_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa"); h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.trialRepo = new TrialRepository(ds, 14);
        this.userId = new AuthRepository(ds).createUser("trial@example.com");
    }

    @Test
    void firstTrialStartsSuccessfully() throws Exception {
        assertTrue(trialRepo.startTrial(userId, "device-fp-abc"));
    }

    @Test
    void sameDeviceCannotStartSecondTrial() throws Exception {
        trialRepo.startTrial(userId, "device-fp-xyz");
        UUID other = new AuthRepository(ds).createUser("other@example.com");
        assertFalse(trialRepo.startTrial(other, "device-fp-xyz"));
    }

    @Test
    void trialExpiryIsApproximately14Days() throws Exception {
        trialRepo.startTrial(userId, "device-expiry");
        var info = trialRepo.getTrialInfo(userId);
        assertNotNull(info);
        long now = System.currentTimeMillis() / 1000;
        long expected = now + (14L * 24 * 3600);
        assertTrue(Math.abs(info.expiresAt() - expected) < 3600);
    }

    @Test
    void deviceHasUsedTrialDetectsExistingTrial() throws Exception {
        assertFalse(trialRepo.deviceHasUsedTrial("device-fresh"));
        trialRepo.startTrial(userId, "device-fresh");
        assertTrue(trialRepo.deviceHasUsedTrial("device-fresh"));
    }
}
