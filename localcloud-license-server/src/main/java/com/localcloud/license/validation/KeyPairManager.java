package com.localcloud.license.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.*;
import java.util.Base64;

/**
 * Manages the RSA key pair used to sign license validation JWTs.
 *
 * Priority order for private key:
 * 1. LOCALCLOUD_LICENSE_PRIVATE_KEY env var (base64-encoded DER PKCS8) — throws if set but malformed
 * 2. Generate a new RSA-2048 key pair at startup (ephemeral — log a warning, only when env var is absent)
 *
 * The public key is exposed via getPublicKeyBase64() for the /license/public-key endpoint.
 */
public final class KeyPairManager {

    private static final Logger logger = LoggerFactory.getLogger(KeyPairManager.class);
    private KeyPair keyPair;

    public KeyPairManager() {
        this.keyPair = loadOrGenerate(System.getenv("LOCALCLOUD_LICENSE_PRIVATE_KEY"));
    }

    /** Package-private constructor for testing — accepts the key material directly. */
    KeyPairManager(String privateKeyB64) {
        this.keyPair = loadOrGenerate(privateKeyB64);
    }

    private KeyPair loadOrGenerate(String privateKeyB64) {
        if (privateKeyB64 != null && !privateKeyB64.isBlank()) {
            try {
                byte[] der = Base64.getDecoder().decode(privateKeyB64.strip());
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
                // Derive public key from private key via RSA spec
                RSAPrivateCrtKey rsaPriv = (RSAPrivateCrtKey) privateKey;
                PublicKey publicKey = kf.generatePublic(
                    new RSAPublicKeySpec(rsaPriv.getModulus(), rsaPriv.getPublicExponent()));
                logger.info("License signing key loaded from LOCALCLOUD_LICENSE_PRIVATE_KEY");
                return new KeyPair(publicKey, privateKey);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "LOCALCLOUD_LICENSE_PRIVATE_KEY is set but could not be parsed as RSA PKCS8 key: " + e.getMessage(), e);
            }
        }
        logger.warn("LOCALCLOUD_LICENSE_PRIVATE_KEY not set — generating ephemeral RSA-2048 key pair. " +
                    "Clients will need to fetch the public key from /license/public-key on each restart.");
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key pair generation failed", e);
        }
    }

    public PrivateKey getPrivateKey() { return keyPair.getPrivate(); }
    public PublicKey getPublicKey() { return keyPair.getPublic(); }

    /** Returns the public key as base64-encoded DER (X.509 SubjectPublicKeyInfo format). */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** Returns true if a signing key is configured (always true after construction). */
    public boolean hasKey() {
        return keyPair != null;
    }

    /** Replaces the active key pair at runtime (used after admin generates a new key). */
    public void setKeyPair(KeyPair kp) {
        this.keyPair = kp;
    }
}
