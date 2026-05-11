package com.localcloud.license.keys;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyRepositoryTest {

    private DataSource ds;
    private ApiKeyRepository keyRepo;
    private AuthRepository authRepo;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:keys_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa"); h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.keyRepo = new ApiKeyRepository(ds);
        this.authRepo = new AuthRepository(ds);
        this.userId = authRepo.createUser("keyholder@example.com");
    }

    @Test
    void generateAndStoreOnlineKey() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        assertTrue(rawKey.startsWith("lco_"));
        List<ApiKeyRepository.KeyInfo> keys = keyRepo.listUserKeys(userId);
        assertEquals(1, keys.size());
        assertEquals("pro", keys.get(0).tier());
    }

    @Test
    void revokeKeyRemovesFromActiveList() throws Exception {
        keyRepo.generateOnlineKey(userId, "community");
        List<ApiKeyRepository.KeyInfo> keys = keyRepo.listUserKeys(userId);
        keyRepo.revokeKey(keys.get(0).id(), userId);
        assertTrue(keyRepo.listUserKeys(userId).isEmpty());
    }

    @Test
    void findActiveKeyByHashWorks() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        var info = keyRepo.findActiveKeyByHash(rawKey);
        assertNotNull(info);
        assertEquals("pro", info.tier());
        assertEquals("keyholder@example.com", info.userEmail());
    }

    @Test
    void revokedKeyNotFoundByHash() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        var keys = keyRepo.listUserKeys(userId);
        keyRepo.revokeKey(keys.get(0).id(), userId);
        assertNull(keyRepo.findActiveKeyByHash(rawKey));
    }
}
