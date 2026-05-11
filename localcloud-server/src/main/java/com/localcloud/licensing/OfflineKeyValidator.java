package com.localcloud.licensing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/**
 * Validates offline license keys (lck_ prefix) using Ed25519 digital signatures.
 *
 * Key format: lck_<base64url(version:json_payload:ed25519_signature)>
 * - version: 1 byte (0x01)
 * - json_payload: variable length UTF-8 JSON
 * - signature: 64 bytes Ed25519 (always last 64 bytes)
 *
 * The public key is embedded at compile time. The private key lives only on the license server.
 */
public class OfflineKeyValidator {

    private static final Logger logger = LoggerFactory.getLogger(OfflineKeyValidator.class);
    private static final String PREFIX = "lck_";
    private static final int VERSION = 0x01;
    private static final int SIGNATURE_LENGTH = 64; // Ed25519 signature is always 64 bytes

    private final PublicKey publicKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public OfflineKeyValidator(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /**
     * Validate an offline license key.
     *
     * @param key      the full key string (including lck_ prefix)
     * @param deviceId the current device fingerprint (null to skip device check)
     * @return validation result with tier and email if valid
     */
    public LicenseResult validate(String key, String deviceId) {
        if (key == null || !key.startsWith(PREFIX)) {
            return LicenseResult.invalid("Invalid key prefix — expected 'lck_'");
        }

        if (publicKey == null) {
            return LicenseResult.invalid(
                "No license public key configured. Set LOCALCLOUD_LICENSE_PUBLIC_KEY env var " +
                "or contact support at https://localcloud.dev");
        }

        try {
            String encoded = key.substring(PREFIX.length());
            byte[] combined = Base64.getUrlDecoder().decode(encoded);

            if (combined.length < 1 + SIGNATURE_LENGTH + 2) {
                return LicenseResult.invalid("Key too short");
            }

            int version = combined[0] & 0xFF;
            if (version != VERSION) {
                return LicenseResult.invalid("Unsupported key version: " + version);
            }

            int payloadLength = combined.length - 1 - SIGNATURE_LENGTH;
            byte[] payloadBytes = new byte[payloadLength];
            byte[] signatureBytes = new byte[SIGNATURE_LENGTH];
            System.arraycopy(combined, 1, payloadBytes, 0, payloadLength);
            System.arraycopy(combined, 1 + payloadLength, signatureBytes, 0, SIGNATURE_LENGTH);

            // Verify Ed25519 signature
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(payloadBytes);
            if (!sig.verify(signatureBytes)) {
                return LicenseResult.invalid("Invalid signature — key may be tampered");
            }

            // Parse JSON payload
            String json = new String(payloadBytes, StandardCharsets.UTF_8);
            JsonNode claims = mapper.readTree(json);

            // Check expiry
            long expires = claims.path("expires").asLong(0);
            if (Instant.now().getEpochSecond() > expires) {
                return LicenseResult.invalid("License expired — renew at https://localcloud.dev/pricing");
            }

            // Check device binding (if present in token)
            String boundDeviceId = claims.path("device_id").asText(null);
            if (boundDeviceId != null && !boundDeviceId.isBlank()) {
                if (deviceId == null || !deviceId.equals(boundDeviceId)) {
                    return LicenseResult.invalid("License bound to different device");
                }
            }

            // Extract tier and email
            String tierStr = claims.path("tier").asText("community");
            String email = claims.path("email").asText("unknown");
            LicenseTier tier = LicenseTier.fromString(tierStr);

            return LicenseResult.valid(tier, email, deviceId, expires);

        } catch (Exception e) {
            logger.debug("Offline key validation failed: {}", e.getMessage());
            return LicenseResult.invalid("Key validation failed: " + e.getMessage());
        }
    }
}
