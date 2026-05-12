package com.localcloud.license.validation;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class KeyPairManagerTest {

    @Test
    void ephemeralGenerationProducesValidKeyPair() {
        // LOCALCLOUD_LICENSE_PRIVATE_KEY not set in test environment
        KeyPairManager manager = new KeyPairManager();
        assertNotNull(manager.getPrivateKey());
        assertNotNull(manager.getPublicKey());
        assertEquals("RSA", manager.getPrivateKey().getAlgorithm());
        assertEquals("RSA", manager.getPublicKey().getAlgorithm());
    }

    @Test
    void getPublicKeyBase64ReturnsNonBlankBase64() {
        KeyPairManager manager = new KeyPairManager();
        String b64 = manager.getPublicKeyBase64();
        assertNotNull(b64);
        assertFalse(b64.isBlank());
        // Must be valid base64 that decodes to a non-empty byte array
        byte[] decoded = Base64.getDecoder().decode(b64);
        assertTrue(decoded.length > 0);
    }

    @Test
    void twoEphemeralManagersProduceDifferentKeys() {
        KeyPairManager m1 = new KeyPairManager();
        KeyPairManager m2 = new KeyPairManager();
        // Different instances — different ephemeral keys
        assertNotEquals(m1.getPublicKeyBase64(), m2.getPublicKeyBase64());
    }

    @Test
    void publicKeyBase64IsX509EncodedFormat() throws Exception {
        KeyPairManager manager = new KeyPairManager();
        byte[] der = Base64.getDecoder().decode(manager.getPublicKeyBase64());
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        // Should not throw — must be valid X.509 SubjectPublicKeyInfo DER
        java.security.PublicKey reconstructed = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(der));
        assertNotNull(reconstructed);
        assertEquals(manager.getPublicKey(), reconstructed);
    }
}
