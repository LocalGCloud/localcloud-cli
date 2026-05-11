package com.localcloud.licensing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class LicenseManagerTest {

    @TempDir
    Path tempDir;

    private static KeyPair testKeyPair;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        testKeyPair = kpg.generateKeyPair();
    }

    @Test
    void noKeyAndBypassServerReturnsProInDevMode() {
        LicenseManager mgr = new LicenseManager(null, "none", tempDir, testKeyPair.getPublic());
        LicenseResult result = mgr.validate();
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void offlineKeyIsValidated() throws Exception {
        String key = makeOfflineKey(
                """
                {"email":"user@co.com","tier":"pro","expires":9999999999,"issued":1000000000,"offline":true}
                """.trim());

        LicenseManager mgr = new LicenseManager(key, "none", tempDir, testKeyPair.getPublic());
        LicenseResult result = mgr.validate();
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void onlineKeyInBypassModeIsAccepted() {
        LicenseManager mgr = new LicenseManager("lco_somekey", "none", tempDir, testKeyPair.getPublic());
        LicenseResult result = mgr.validate();
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void expiredOfflineKeyIsRejected() throws Exception {
        String key = makeOfflineKey(
                """
                {"email":"user@co.com","tier":"pro","expires":1000000000,"issued":999999999,"offline":true}
                """.trim());

        LicenseManager mgr = new LicenseManager(key, "none", tempDir, testKeyPair.getPublic());
        LicenseResult result = mgr.validate();
        assertFalse(result.isValid());
    }

    @Test
    void unknownPrefixIsRejected() {
        LicenseManager mgr = new LicenseManager("xyz_badkey", "none", tempDir, testKeyPair.getPublic());
        LicenseResult result = mgr.validate();
        assertFalse(result.isValid());
    }

    private String makeOfflineKey(String json) throws Exception {
        byte[] payloadBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(testKeyPair.getPrivate());
        sig.update(payloadBytes);
        byte[] signature = sig.sign();
        byte[] combined = new byte[1 + payloadBytes.length + signature.length];
        combined[0] = 0x01;
        System.arraycopy(payloadBytes, 0, combined, 1, payloadBytes.length);
        System.arraycopy(signature, 0, combined, 1 + payloadBytes.length, signature.length);
        return "lck_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }
}
