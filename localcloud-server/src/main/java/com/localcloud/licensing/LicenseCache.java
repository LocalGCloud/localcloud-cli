package com.localcloud.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Tamper-resistant license cache.
 *
 * Stores the validated license result + metadata in an HMAC-signed binary file.
 * The HMAC key is derived from an embedded secret + device fingerprint,
 * so the cache is both tamper-proof and device-bound.
 *
 * File format (token.bin):
 *   [4 bytes]  magic number 0x4C434C43 ("LCLC")
 *   [4 bytes]  format version (1)
 *   [8 bytes]  boot counter (monotonic)
 *   [8 bytes]  last-seen epoch seconds
 *   [4 bytes]  payload length
 *   [N bytes]  JSON payload (LicenseResult fields)
 *   [32 bytes] HMAC-SHA256 of everything above
 */
public class LicenseCache {

    private static final Logger logger = LoggerFactory.getLogger(LicenseCache.class);
    private static final int MAGIC = 0x4C434C43; // "LCLC"
    private static final int FORMAT_VERSION = 1;
    private static final int HMAC_LENGTH = 32;
    private static final String EMBEDDED_SECRET = "lc-cache-v1-k8x2m9";

    private final Path cacheDir;
    private final byte[] hmacKey;
    private final ObjectMapper mapper = new ObjectMapper();

    private long bootCount = 0;
    private long lastSeenTimestamp = 0;

    public LicenseCache(Path dataDir, String deviceFingerprint) {
        this.cacheDir = dataDir.resolve(".license");
        this.hmacKey = deriveKey(deviceFingerprint);
    }

    public void write(LicenseResult result) {
        try {
            Files.createDirectories(cacheDir);

            LicenseCacheData existing = readRaw();
            this.bootCount = (existing != null ? existing.bootCount : bootCount) + 1;
            this.lastSeenTimestamp = Instant.now().getEpochSecond();

            String jsonPayload = mapper.writeValueAsString(new CachedLicense(
                    result.tier().name(), result.email(), result.deviceId(), result.expiresEpoch()));

            byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(MAGIC);
            dos.writeInt(FORMAT_VERSION);
            dos.writeLong(bootCount);
            dos.writeLong(lastSeenTimestamp);
            dos.writeInt(payloadBytes.length);
            dos.write(payloadBytes);
            dos.flush();

            byte[] data = baos.toByteArray();
            byte[] hmac = computeHmac(data);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(data);
            out.write(hmac);

            Files.write(cacheDir.resolve("token.bin"), out.toByteArray());
        } catch (Exception e) {
            logger.warn("Failed to write license cache: {}", e.getMessage());
        }
    }

    public LicenseResult read() {
        LicenseCacheData data = readRaw();
        if (data == null) return null;

        this.bootCount = data.bootCount;
        this.lastSeenTimestamp = data.lastSeen;

        return LicenseResult.valid(
                LicenseTier.fromString(data.license.tier),
                data.license.email,
                data.license.deviceId,
                data.license.expiresEpoch);
    }

    public long getBootCount() {
        return bootCount;
    }

    public long getLastSeenTimestamp() {
        return lastSeenTimestamp;
    }

    public boolean isWithinGracePeriod(int graceHours) {
        if (lastSeenTimestamp == 0) return false;
        long now = Instant.now().getEpochSecond();
        long graceSeconds = graceHours * 3600L;
        return (now - lastSeenTimestamp) < graceSeconds;
    }

    private LicenseCacheData readRaw() {
        Path cacheFile = cacheDir.resolve("token.bin");
        if (!Files.exists(cacheFile)) return null;

        try {
            byte[] allBytes = Files.readAllBytes(cacheFile);
            if (allBytes.length < 4 + 4 + 8 + 8 + 4 + HMAC_LENGTH) return null;

            byte[] data = new byte[allBytes.length - HMAC_LENGTH];
            byte[] storedHmac = new byte[HMAC_LENGTH];
            System.arraycopy(allBytes, 0, data, 0, data.length);
            System.arraycopy(allBytes, data.length, storedHmac, 0, HMAC_LENGTH);

            byte[] computedHmac = computeHmac(data);
            if (!MessageDigest.isEqual(computedHmac, storedHmac)) {
                logger.warn("License cache HMAC mismatch — file tampered or wrong device");
                return null;
            }

            DataInputStream dis = new DataInputStream(new java.io.ByteArrayInputStream(data));
            int magic = dis.readInt();
            if (magic != MAGIC) return null;
            int version = dis.readInt();
            if (version != FORMAT_VERSION) return null;

            long bc = dis.readLong();
            long ls = dis.readLong();
            int payloadLen = dis.readInt();
            byte[] payloadBytes = new byte[payloadLen];
            dis.readFully(payloadBytes);

            CachedLicense license = mapper.readValue(payloadBytes, CachedLicense.class);
            return new LicenseCacheData(bc, ls, license);

        } catch (Exception e) {
            logger.debug("Failed to read license cache: {}", e.getMessage());
            return null;
        }
    }

    private byte[] deriveKey(String deviceFingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(EMBEDDED_SECRET.getBytes(StandardCharsets.UTF_8));
            md.update(deviceFingerprint.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private byte[] computeHmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    private record LicenseCacheData(long bootCount, long lastSeen, CachedLicense license) {}

    public record CachedLicense(String tier, String email, String deviceId, long expiresEpoch) {
        @SuppressWarnings("unused")
        public CachedLicense() { this("community", "", "", 0); }
    }
}
