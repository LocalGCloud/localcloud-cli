package com.localcloud.sync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CredentialEncryptionTest {

    @Test
    void encryptDecrypt_roundTrip() throws Exception {
        String key = CredentialEncryption.generateKey();
        CredentialEncryption enc = new CredentialEncryption(key);
        String original = "{\"access_token\":\"ya29.secret\",\"refresh_token\":\"1//rt_xxx\"}";
        String encrypted = enc.encrypt(original);
        assertNotEquals(original, encrypted);
        assertFalse(encrypted.contains("secret"));
        assertEquals(original, enc.decrypt(encrypted));
    }

    @Test
    void differentEncryptions_produceDifferentCiphertext() throws Exception {
        String key = CredentialEncryption.generateKey();
        CredentialEncryption enc = new CredentialEncryption(key);
        String text = "same text";
        String e1 = enc.encrypt(text);
        String e2 = enc.encrypt(text);
        assertNotEquals(e1, e2); // Different IVs
        assertEquals(text, enc.decrypt(e1));
        assertEquals(text, enc.decrypt(e2));
    }

    @Test
    void wrongKey_failsDecryption() throws Exception {
        String key1 = CredentialEncryption.generateKey();
        String key2 = CredentialEncryption.generateKey();
        CredentialEncryption enc1 = new CredentialEncryption(key1);
        CredentialEncryption enc2 = new CredentialEncryption(key2);
        String encrypted = enc1.encrypt("secret data");
        assertThrows(Exception.class, () -> enc2.decrypt(encrypted));
    }

    @Test
    void generateKey_produces256bit() throws Exception {
        String key = CredentialEncryption.generateKey();
        byte[] bytes = java.util.Base64.getDecoder().decode(key);
        assertEquals(32, bytes.length); // 256 bits = 32 bytes
    }

    // -----------------------------------------------------------------------
    // Edge cases — empty string
    // -----------------------------------------------------------------------

    @Test
    void encrypt_emptyString() throws Exception {
        String key = CredentialEncryption.generateKey();
        CredentialEncryption enc = new CredentialEncryption(key);
        String encrypted = enc.encrypt("");
        assertEquals("", enc.decrypt(encrypted));
    }

    // -----------------------------------------------------------------------
    // Edge cases — unicode text with emojis
    // -----------------------------------------------------------------------

    @Test
    void encrypt_unicodeText() throws Exception {
        String key = CredentialEncryption.generateKey();
        CredentialEncryption enc = new CredentialEncryption(key);
        String original = "\u65e5\u672c\u8a9e\u30c6\u30b9\u30c8 \ud83d\udd10 \u00e9mojis";
        assertEquals(original, enc.decrypt(enc.encrypt(original)));
    }

    // -----------------------------------------------------------------------
    // Edge cases — large payload (100KB)
    // -----------------------------------------------------------------------

    @Test
    void encrypt_largePayload() throws Exception {
        String key = CredentialEncryption.generateKey();
        CredentialEncryption enc = new CredentialEncryption(key);
        String large = "x".repeat(100000); // 100KB
        assertEquals(large, enc.decrypt(enc.encrypt(large)));
    }
}
