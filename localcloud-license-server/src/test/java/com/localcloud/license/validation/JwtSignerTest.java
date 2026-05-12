package com.localcloud.license.validation;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtSignerTest {

    private KeyPairManager keyPairManager;
    private JwtSigner signer;

    @BeforeEach
    void setUp() {
        keyPairManager = new KeyPairManager();
        signer = new JwtSigner(keyPairManager.getPrivateKey());
    }

    @Test
    void signedTokenParsesBackCorrectly() {
        long expiresEpoch = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signer.sign("pro", "user@example.com", "device-abc", expiresEpoch);

        assertNotNull(jwt);
        assertFalse(jwt.isBlank());
        // JWT has 3 dot-separated parts
        assertEquals(3, jwt.split("\\.").length);

        Claims claims = Jwts.parser()
            .verifyWith(keyPairManager.getPublicKey())
            .build()
            .parseSignedClaims(jwt)
            .getPayload();

        assertEquals("localcloud-license", claims.getIssuer());
        assertEquals("user@example.com", claims.getSubject());
        assertEquals("pro", claims.get("tier", String.class));
        assertEquals("device-abc", claims.get("device_id", String.class));
        // Expiry should be within 1 second of what we set
        long actualExpires = claims.getExpiration().toInstant().getEpochSecond();
        assertEquals(expiresEpoch, actualExpires, 1L);
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        long expiresEpoch = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signer.sign("pro", "user@example.com", "device-abc", expiresEpoch);

        // Different key pair — verification must fail
        KeyPairManager otherManager = new KeyPairManager();
        assertThrows(Exception.class, () ->
            Jwts.parser()
                .verifyWith(otherManager.getPublicKey())
                .build()
                .parseSignedClaims(jwt));
    }

    @Test
    void nullEmailDefaultsToUnknown() {
        long expiresEpoch = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signer.sign("community", null, "device-xyz", expiresEpoch);

        Claims claims = Jwts.parser()
            .verifyWith(keyPairManager.getPublicKey())
            .build()
            .parseSignedClaims(jwt)
            .getPayload();

        assertEquals("unknown", claims.getSubject());
        assertEquals("community", claims.get("tier", String.class));
    }

    @Test
    void nullDeviceIdDefaultsToEmpty() {
        long expiresEpoch = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signer.sign("pro", "user@example.com", null, expiresEpoch);

        Claims claims = Jwts.parser()
            .verifyWith(keyPairManager.getPublicKey())
            .build()
            .parseSignedClaims(jwt)
            .getPayload();

        assertEquals("", claims.get("device_id", String.class));
    }

    @Test
    void expiredTokenIsRejected() {
        // Expiry in the past
        long pastEpoch = System.currentTimeMillis() / 1000L - 10;
        String jwt = signer.sign("pro", "user@example.com", "device", pastEpoch);

        assertThrows(Exception.class, () ->
            Jwts.parser()
                .verifyWith(keyPairManager.getPublicKey())
                .build()
                .parseSignedClaims(jwt));
    }

    @Test
    void issuerClaimIsLocalcloudLicense() {
        long expiresEpoch = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signer.sign("pro", "test@test.com", "dev", expiresEpoch);

        Claims claims = Jwts.parser()
            .verifyWith(keyPairManager.getPublicKey())
            .requireIssuer("localcloud-license")
            .build()
            .parseSignedClaims(jwt)
            .getPayload();

        assertEquals("localcloud-license", claims.getIssuer());
    }
}
