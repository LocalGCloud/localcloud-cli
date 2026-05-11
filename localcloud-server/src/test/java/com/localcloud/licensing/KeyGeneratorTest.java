package com.localcloud.licensing;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

class KeyGeneratorTest {

    @Test
    void generateKeyPairProducesValidKeys() throws Exception {
        KeyPair kp = KeyGenerator.generateKeyPair();
        assertNotNull(kp.getPublic());
        assertNotNull(kp.getPrivate());
        assertEquals("EdDSA", kp.getPublic().getAlgorithm());
    }

    @Test
    void generateOfflineKeyProducesValidKey() throws Exception {
        KeyPair kp = KeyGenerator.generateKeyPair();
        String key = KeyGenerator.generateOfflineKey(
                kp.getPrivate(), "test@example.com", "pro", null, 365);

        assertTrue(key.startsWith("lck_"), "Should have lck_ prefix");
        OfflineKeyValidator validator = new OfflineKeyValidator(kp.getPublic());
        LicenseResult result = validator.validate(key, null);
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
        assertEquals("test@example.com", result.email());
    }

    @Test
    void generateDeviceBoundOfflineKey() throws Exception {
        KeyPair kp = KeyGenerator.generateKeyPair();
        String key = KeyGenerator.generateOfflineKey(
                kp.getPrivate(), "test@example.com", "enterprise", "abc123", 30);

        OfflineKeyValidator validator = new OfflineKeyValidator(kp.getPublic());
        LicenseResult result = validator.validate(key, "abc123");
        assertTrue(result.isValid());
        LicenseResult wrong = validator.validate(key, "wrong");
        assertFalse(wrong.isValid());
    }

    @Test
    void publicKeyEncodingRoundTrips() throws Exception {
        KeyPair kp = KeyGenerator.generateKeyPair();
        String encoded = KeyGenerator.encodePublicKey(kp.getPublic());
        assertNotNull(encoded);
        assertFalse(encoded.isBlank());

        java.security.PublicKey decoded = KeyGenerator.decodePublicKey(encoded);
        assertEquals(kp.getPublic(), decoded);
    }

    @Test
    void generateOnlineKeyHasCorrectFormat() {
        String key = KeyGenerator.generateOnlineKey();
        assertTrue(key.startsWith("lco_"));
        assertTrue(key.length() > 10);
    }
}
