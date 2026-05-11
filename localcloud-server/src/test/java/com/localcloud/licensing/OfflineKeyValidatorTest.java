package com.localcloud.licensing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class OfflineKeyValidatorTest {

    private static KeyPair testKeyPair;
    private static OfflineKeyValidator validator;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        testKeyPair = kpg.generateKeyPair();
        validator = new OfflineKeyValidator(testKeyPair.getPublic());
    }

    @Test
    void validKeyIsAccepted() throws Exception {
        String key = generateTestKey(
                """
                {"email":"test@example.com","tier":"pro","expires":9999999999,"issued":1000000000,"offline":true}
                """.trim(),
                testKeyPair.getPrivate());
        LicenseResult result = validator.validate(key, null);
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
        assertEquals("test@example.com", result.email());
    }

    @Test
    void expiredKeyIsRejected() throws Exception {
        String key = generateTestKey(
                """
                {"email":"test@example.com","tier":"pro","expires":1000000000,"issued":999999999,"offline":true}
                """.trim(),
                testKeyPair.getPrivate());
        LicenseResult result = validator.validate(key, null);
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("expired"));
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        String key = generateTestKey(
                """
                {"email":"test@example.com","tier":"pro","expires":9999999999,"issued":1000000000,"offline":true}
                """.trim(),
                testKeyPair.getPrivate());
        // Use a replacement char guaranteed to differ from the original at position 5
        char original = key.charAt(5);
        char replacement = (original == 'X') ? 'Y' : 'X';
        String tampered = key.substring(0, 5) + replacement + key.substring(6);
        LicenseResult result = validator.validate(tampered, null);
        assertFalse(result.isValid());
    }

    @Test
    void wrongPublicKeyRejectsKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair otherKeyPair = kpg.generateKeyPair();
        OfflineKeyValidator wrongValidator = new OfflineKeyValidator(otherKeyPair.getPublic());

        String key = generateTestKey(
                """
                {"email":"test@example.com","tier":"pro","expires":9999999999,"issued":1000000000,"offline":true}
                """.trim(),
                testKeyPair.getPrivate());
        LicenseResult result = wrongValidator.validate(key, null);
        assertFalse(result.isValid());
    }

    @Test
    void deviceBoundKeyRejectsWrongDevice() throws Exception {
        String key = generateTestKey(
                """
                {"email":"test@example.com","tier":"pro","expires":9999999999,"issued":1000000000,"offline":true,"device_id":"abc123"}
                """.trim(),
                testKeyPair.getPrivate());
        LicenseResult result = validator.validate(key, "wrong-device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("device"));
    }

    @Test
    void deviceBoundKeyAcceptsCorrectDevice() throws Exception {
        String key = generateTestKey(
                """
                {"email":"test@example.com","tier":"pro","expires":9999999999,"issued":1000000000,"offline":true,"device_id":"abc123"}
                """.trim(),
                testKeyPair.getPrivate());
        LicenseResult result = validator.validate(key, "abc123");
        assertTrue(result.isValid());
    }

    @Test
    void floatingKeyAcceptsAnyDevice() throws Exception {
        String key = generateTestKey(
                """
                {"email":"test@example.com","tier":"pro","expires":9999999999,"issued":1000000000,"offline":true}
                """.trim(),
                testKeyPair.getPrivate());
        LicenseResult result = validator.validate(key, "any-device");
        assertTrue(result.isValid());
    }

    @Test
    void invalidPrefixIsRejected() {
        LicenseResult result = validator.validate("lco_somekey", null);
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("prefix"));
    }

    @Test
    void nullPublicKeyRejectsWithClearMessage() {
        OfflineKeyValidator nullValidator = new OfflineKeyValidator(null);
        LicenseResult result = nullValidator.validate("lck_someencodedkey", null);
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("public key"),
                "Error should mention public key, was: " + result.errorMessage());
    }

    private static String generateTestKey(String jsonPayload, PrivateKey privateKey) throws Exception {
        byte[] payloadBytes = jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payloadBytes);
        byte[] signature = sig.sign();

        byte[] combined = new byte[1 + payloadBytes.length + signature.length];
        combined[0] = 0x01; // version
        System.arraycopy(payloadBytes, 0, combined, 1, payloadBytes.length);
        System.arraycopy(signature, 0, combined, 1 + payloadBytes.length, signature.length);

        return "lck_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }
}
