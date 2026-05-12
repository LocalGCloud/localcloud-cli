package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

class LicenseIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void fullOfflineKeyLifecycle() throws Exception {
        // 1. Generate keypair (done once by admin)
        KeyPair kp = KeyGenerator.generateKeyPair();

        // 2. Generate offline key for a Pro user, valid 365 days, floating (no device binding)
        String key = KeyGenerator.generateOfflineKey(kp.getPrivate(), "customer@corp.com", "pro", null, 365);
        assertTrue(key.startsWith("lck_"));

        // 3. Validate via LicenseManager
        LicenseManager mgr = new LicenseManager(key, "none", tempDir, kp.getPublic());
        LicenseResult result = mgr.validate();

        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
        assertEquals("customer@corp.com", result.email());

        // 4. Verify cache was written
        LicenseCache cache = new LicenseCache(tempDir, mgr.getDeviceId());
        LicenseResult cached = cache.read();
        assertNotNull(cached);
        assertEquals(LicenseTier.PRO, cached.tier());
    }

    @Test
    void communityTierGatesServices() throws Exception {
        KeyPair kp = KeyGenerator.generateKeyPair();
        String key = KeyGenerator.generateOfflineKey(kp.getPrivate(), "free@user.com", "community", null, 365);

        LicenseManager mgr = new LicenseManager(key, "none", tempDir, kp.getPublic());
        LicenseResult result = mgr.validate();

        assertTrue(result.isValid());
        assertEquals(LicenseTier.COMMUNITY, result.tier());

        // Community includes community-tier services
        assertTrue(result.tier().includes(LicenseTier.COMMUNITY));
        // Community does not include pro-tier services (spanner, bigtable, etc.)
        assertFalse(result.tier().includes(LicenseTier.PRO));
    }

    @Test
    void bypassModeWithNoKeyAllowsEverything() {
        LicenseManager mgr = new LicenseManager(null, "none", tempDir, null);
        LicenseResult result = mgr.validate();

        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void publicKeyRoundTripWorksWithValidator() throws Exception {
        // Simulate production flow: encode pubkey to string, embed it, decode at runtime
        KeyPair kp = KeyGenerator.generateKeyPair();
        String pubKeyString = KeyGenerator.encodePublicKey(kp.getPublic());

        // Later, at runtime:
        java.security.PublicKey decodedPub = KeyGenerator.decodePublicKey(pubKeyString);

        // Generate key with original private key
        String key = KeyGenerator.generateOfflineKey(kp.getPrivate(), "rt@test.com", "team", null, 30);

        // Validate with decoded public key
        OfflineKeyValidator validator = new OfflineKeyValidator(decodedPub);
        LicenseResult result = validator.validate(key, null);
        assertTrue(result.isValid());
        assertEquals(LicenseTier.TEAM, result.tier());
    }
}
