package com.localcloud.license.admin;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import com.localcloud.license.keys.ApiKeyRepository;
import com.localcloud.license.trial.TrialRepository;
import com.localcloud.license.validation.DeviceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class AdminStatsRepositoryTest {

    private DataSource ds;
    private AdminStatsRepository statsRepo;
    private AuthRepository authRepo;
    private ApiKeyRepository keyRepo;
    private TrialRepository trialRepo;
    private DeviceTracker deviceTracker;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:stats_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa"); h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.statsRepo = new AdminStatsRepository(ds);
        this.authRepo = new AuthRepository(ds);
        this.keyRepo = new ApiKeyRepository(ds);
        this.trialRepo = new TrialRepository(ds, 14);
        this.deviceTracker = new DeviceTracker(ds);
    }

    @Test
    void emptyDbReturnsZeroCounts() {
        var stats = statsRepo.getStats();
        assertEquals(0L, stats.get("total_keys"));
        assertEquals(0L, stats.get("active_keys"));
        assertEquals(0L, stats.get("total_users"));
        assertEquals(0L, stats.get("verified_users"));
        assertEquals(0L, stats.get("total_devices"));
        assertEquals(0L, stats.get("active_trials"));
        assertEquals(0L, stats.get("keys_pro"));
        assertEquals(0L, stats.get("keys_trial"));
        assertEquals(0L, stats.get("keys_community"));
    }

    @Test
    void countsReflectSeededData() throws Exception {
        var u1 = authRepo.createUser("user1@example.com").userId();
        var u2 = authRepo.createUser("user2@example.com").userId();
        authRepo.markEmailVerified("user1@example.com");

        keyRepo.generateOnlineKey(u1, "pro");
        keyRepo.generateOnlineKey(u1, "community");
        keyRepo.generateOnlineKey(u2, "trial");

        trialRepo.startTrial(u1, "device-1");
        deviceTracker.recordDevice(u1, "device-1");

        var stats = statsRepo.getStats();
        assertEquals(3L, stats.get("total_keys"));
        assertEquals(3L, stats.get("active_keys"));
        assertEquals(0L, stats.get("expired_keys"));
        assertEquals(2L, stats.get("total_users"));
        assertEquals(1L, stats.get("verified_users"));
        assertEquals(1L, stats.get("active_trials"));
        assertEquals(0L, stats.get("expired_trials"));
        assertEquals(1L, stats.get("total_devices"));
        assertEquals(1L, stats.get("keys_pro"));
        assertEquals(1L, stats.get("keys_trial"));
        assertEquals(1L, stats.get("keys_community"));
    }
}
