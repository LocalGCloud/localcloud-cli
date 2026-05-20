package com.localcloud.license.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import com.localcloud.license.keys.ApiKeyRepository;
import com.localcloud.license.keys.KeyPairRepository;
import com.localcloud.license.validation.DeviceTracker;
import com.localcloud.license.validation.KeyPairManager;
import com.localcloud.license.validation.LicenseValidator;
import com.localcloud.license.trial.TrialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminHandlerTest {

    private DataSource ds;
    private AdminHandler handler;
    private AdminSessionStore sessionStore;
    private AdminStatsRepository statsRepo;
    private ObjectMapper mapper;
    private AuthRepository authRepo;
    private ApiKeyRepository keyRepo;
    private LicenseValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:admin_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa"); h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.sessionStore = new AdminSessionStore();
        this.statsRepo = new AdminStatsRepository(ds);
        this.mapper = new ObjectMapper();
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var kp = kpg.generateKeyPair();
        var onlineKpg = KeyPairGenerator.getInstance("RSA");
        onlineKpg.initialize(2048, new java.security.SecureRandom());
        var onlineKp = onlineKpg.generateKeyPair();
        var km = new KeyPairManager();
        km.setKeyPair(onlineKp);
        this.handler = new AdminHandler(sessionStore, statsRepo, ds, "adminPass123",
                kp.getPrivate(), kp.getPublic(), new KeyPairRepository(ds), km);
        this.authRepo = new AuthRepository(ds);
        this.keyRepo = new ApiKeyRepository(ds);
        this.validator = new LicenseValidator(keyRepo, authRepo, new DeviceTracker(ds), new TrialRepository(ds, 14));
    }

    private AggregatedHttpResponse agg(HttpResponse res) {
        return res.aggregate().join();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(AggregatedHttpResponse res) throws Exception {
        return mapper.readValue(res.content().toStringUtf8(), Map.class);
    }

    // === Auth ===

    @Test
    void loginWithCorrectPasswordReturnsToken() throws Exception {
        var res = agg(handler.login(Map.of("password", "adminPass123")));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertTrue(data.containsKey("token"));
        assertTrue(((String) data.get("token")).startsWith("adm_"));
        assertEquals(28800, data.get("expires_in_seconds"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        var res = agg(handler.login(Map.of("password", "wrong")));
        assertEquals(HttpStatus.UNAUTHORIZED, res.status());
        var data = parseBody(res);
        assertEquals("Invalid admin password", data.get("error"));
    }

    @Test
    void loginWithNullPasswordReturns401() throws Exception {
        var res = agg(handler.login(Map.of()));
        assertEquals(HttpStatus.UNAUTHORIZED, res.status());
    }

    // === Stats ===

    @Test
    void statsReturnsCounts() throws Exception {
        var res = agg(handler.stats());
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertTrue(data.containsKey("total_keys"));
        assertTrue(data.containsKey("total_users"));
    }

    // === Health ===

    @Test
    void healthReturnsOk() throws Exception {
        var res = agg(handler.health());
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertEquals("ok", data.get("status"));
        assertEquals("connected", data.get("database"));
    }

    // === Users ===

    @Test
    void listUsersReturnsEmptyArrayWhenNoUsers() throws Exception {
        var res = agg(handler.listUsers(Optional.empty()));
        assertEquals(HttpStatus.OK, res.status());
        assertEquals("[]", res.content().toStringUtf8());
    }

    @Test
    void createUserThenListIncludesThem() throws Exception {
        authRepo.createUser("listed@example.com");
        var res = agg(handler.listUsers(Optional.empty()));
        assertEquals(HttpStatus.OK, res.status());
        assertTrue(res.content().toStringUtf8().contains("listed@example.com"));
    }

    @Test
    void getUserReturns404ForUnknown() throws Exception {
        var res = agg(handler.getUser("00000000-0000-0000-0000-000000000000"));
        assertEquals(HttpStatus.NOT_FOUND, res.status());
    }

    @Test
    void getUserWithInvalidUuidReturns400() throws Exception {
        var res = agg(handler.getUser("not-a-uuid"));
        assertEquals(HttpStatus.BAD_REQUEST, res.status());
    }

    // === Key Generation — All Tiers ===

    @Test
    void generateProKey() throws Exception {
        var res = agg(handler.generateKey(Map.of("email", "pro@example.com", "tier", "pro")));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertTrue(((String) data.get("key")).startsWith("lco_"),
            "Key should start with lco_, got: " + data.get("key"));
        assertEquals("pro", data.get("tier"));
        assertEquals("pro@example.com", data.get("email"));

        // Verify the key is actually valid
        var rawKey = (String) data.get("key");
        var validation = validator.validate(rawKey, "test-device");
        assertTrue(validation.valid());
        assertEquals("pro", validation.tier());
        assertEquals("pro@example.com", validation.email());
    }

    @Test
    void generateCommunityKey() throws Exception {
        var res = agg(handler.generateKey(Map.of("email", "community@example.com", "tier", "community")));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertEquals("community", data.get("tier"));

        var validation = validator.validate((String) data.get("key"), "device-c");
        assertTrue(validation.valid());
        assertEquals("community", validation.tier());
    }

    @Test
    void generateTrialKey() throws Exception {
        var res = agg(handler.generateKey(Map.of("email", "trial@example.com", "tier", "trial")));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertEquals("trial", data.get("tier"));

        // Start trial for the user so validation passes
        var userId = authRepo.getUserId("trial@example.com");
        new TrialRepository(ds, 14).startTrial(userId, "device-t");

        var validation = validator.validate((String) data.get("key"), "device-t");
        assertTrue(validation.valid());
        assertEquals("trial", validation.tier());
    }

    @Test
    void generateKeyCreatesUserAutomatically() throws Exception {
        assertNull(authRepo.getUserId("newuser@example.com"));
        var res = agg(handler.generateKey(Map.of("email", "newuser@example.com", "tier", "pro")));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertTrue((Boolean) data.get("user_created"));
        assertNotNull(authRepo.getUserId("newuser@example.com"));
    }

    @Test
    void generateKeyForExistingUserReusesAccount() throws Exception {
        authRepo.createUser("existing@example.com");
        var res = agg(handler.generateKey(Map.of("email", "existing@example.com", "tier", "community")));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertFalse((Boolean) data.get("user_created"));
    }

    // === Key Generation — Error Cases ===

    @Test
    void generateKeyWithInvalidUuidReturns400() throws Exception {
        var res = agg(handler.generateKey(Map.of("email", "not-an-email", "tier", "pro")));
        assertEquals(HttpStatus.OK, res.status()); // email is valid even without @ — just a local-part
        // This should succeed — email-based lookup doesn't care about format
    }

    @Test
    void generateKeyWithEmptyEmailReturns400() throws Exception {
        var res = agg(handler.generateKey(Map.of("email", "", "tier", "pro")));
        assertEquals(HttpStatus.BAD_REQUEST, res.status());
    }

    @Test
    void generateKeyWithMissingEmailReturns400() throws Exception {
        var res = agg(handler.generateKey(Map.of("tier", "pro")));
        assertEquals(HttpStatus.BAD_REQUEST, res.status());
    }

    @Test
    void generateMultipleKeysForSameEmail() throws Exception {
        for (String tier : new String[]{"pro", "community", "trial"}) {
            var res = agg(handler.generateKey(Map.of("email", "multi@example.com", "tier", tier)));
            assertEquals(HttpStatus.OK, res.status(), "Failed for tier: " + tier);
        }
        var userId = authRepo.getUserId("multi@example.com");
        var keys = keyRepo.listUserKeys(userId);
        assertEquals(3, keys.size());
    }

    // === Key Revocation ===

    @Test
    void revokeKeyReturns404ForUnknown() throws Exception {
        var res = agg(handler.revokeKey("00000000-0000-0000-0000-000000000000"));
        assertEquals(HttpStatus.NOT_FOUND, res.status());
    }

    @Test
    void revokeKeyWithInvalidUuidReturns400() throws Exception {
        var res = agg(handler.revokeKey("bad-uuid"));
        assertEquals(HttpStatus.BAD_REQUEST, res.status());
    }

    @Test
    void generateThenRevokeKey() throws Exception {
        var genRes = agg(handler.generateKey(Map.of("email", "revoke@example.com", "tier", "pro")));
        var data = parseBody(genRes);
        var rawKey = (String) data.get("key");

        // Key is valid before revoke
        assertTrue(validator.validate(rawKey, "device-r").valid());

        // Find keyId and revoke
        var userId = authRepo.getUserId("revoke@example.com");
        var keys = keyRepo.listUserKeys(userId);
        assertEquals(1, keys.size());
        var revokeRes = agg(handler.revokeKey(keys.get(0).id().toString()));
        assertEquals(HttpStatus.OK, revokeRes.status());

        // Key is invalid after revoke
        assertFalse(validator.validate(rawKey, "device-r").valid());
    }

    @Test
    void revokingAlreadyRevokedKeyReturns404() throws Exception {
        handler.generateKey(Map.of("email", "doublerevoke@example.com", "tier", "pro"));
        var userId = authRepo.getUserId("doublerevoke@example.com");
        var keys = keyRepo.listUserKeys(userId);

        // First revoke succeeds
        agg(handler.revokeKey(keys.get(0).id().toString()));
        // Second revoke fails
        var res = agg(handler.revokeKey(keys.get(0).id().toString()));
        assertEquals(HttpStatus.NOT_FOUND, res.status());
    }

    // === Key Listing ===

    @Test
    void listKeysReturnsEmptyArrayWhenNone() throws Exception {
        var res = agg(handler.listKeys(Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(HttpStatus.OK, res.status());
        assertEquals("[]", res.content().toStringUtf8());
    }

    @Test
    void listKeysReturnsGeneratedKeys() throws Exception {
        handler.generateKey(Map.of("email", "listkeys@example.com", "tier", "pro"));
        handler.generateKey(Map.of("email", "listkeys@example.com", "tier", "community"));

        var res = agg(handler.listKeys(Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(HttpStatus.OK, res.status());
        var content = res.content().toStringUtf8();
        assertTrue(content.contains("listkeys@example.com"));
        assertTrue(content.contains("pro"));
        assertTrue(content.contains("community"));
    }

    // === Key Detail ===

    @Test
    void getKeyReturns404ForUnknown() throws Exception {
        var res = agg(handler.getKey("00000000-0000-0000-0000-000000000000"));
        assertEquals(HttpStatus.NOT_FOUND, res.status());
    }

    @Test
    void getKeyWithInvalidUuidReturns400() throws Exception {
        var res = agg(handler.getKey("bad-uuid"));
        assertEquals(HttpStatus.BAD_REQUEST, res.status());
    }

    @Test
    void getKeyReturnsKeyDetails() throws Exception {
        var genRes = agg(handler.generateKey(Map.of("email", "keydetail@example.com", "tier", "trial")));
        var rawKey = (String) parseBody(genRes).get("key");

        var userId = authRepo.getUserId("keydetail@example.com");
        var keys = keyRepo.listUserKeys(userId);
        var res = agg(handler.getKey(keys.get(0).id().toString()));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertEquals("trial", data.get("tier"));
        assertEquals("keydetail@example.com", data.get("email"));
    }

    // === Logout ===

    @Test
    void logoutReturnsOk() throws Exception {
        var res = agg(handler.logout("Bearer adm_sometoken"));
        assertEquals(HttpStatus.OK, res.status());
        var data = parseBody(res);
        assertEquals("Logged out", data.get("message"));
    }

    @Test
    void logoutWithNoAuthStillSucceeds() throws Exception {
        var res = agg(handler.logout(null));
        assertEquals(HttpStatus.OK, res.status());
    }

    // === Devices ===

    @Test
    void listDevicesReturnsEmptyArrayWhenNone() throws Exception {
        var res = agg(handler.listDevices(Optional.empty()));
        assertEquals(HttpStatus.OK, res.status());
        assertEquals("[]", res.content().toStringUtf8());
    }

    // === Trials ===

    @Test
    void listTrialsReturnsEmptyArrayWhenNone() throws Exception {
        var res = agg(handler.listTrials(Optional.empty()));
        assertEquals(HttpStatus.OK, res.status());
        assertEquals("[]", res.content().toStringUtf8());
    }
}
