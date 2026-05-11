# API Key & Licensing — Phase 1: Client-Side Validation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add device fingerprinting, Ed25519 offline key validation, online key validation stub, tier-based service gating, and tamper-resistant license cache to the LocalCloud container — without requiring a license server yet.

**Architecture:** A new `licensing` package handles all key validation. Device fingerprint is computed from hardware signals in `/proc`. Offline keys (`lck_`) are self-validating Ed25519 signed tokens with JSON payloads. Online keys (`lco_`) validate against a configurable license server URL (stubbed for Phase 1 with a bypass mode). The entrypoint script runs a license check before supervisord. Service gating uses existing `LOCALCLOUD_ENABLE_*` env vars controlled by the validated tier.

**Tech Stack:** Java 21 (EdDSA built-in), SHA-256 (fingerprinting), HMAC-SHA256 (cache protection), no new dependencies

**Design doc:** `docs/plans/2026-05-10-api-key-licensing-design.md`

---

### Task 1: DeviceFingerprint — Hardware Signal Collection

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/licensing/DeviceFingerprint.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/DeviceFingerprintTest.java`

**Step 1: Write the failing test**

```java
package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceFingerprintTest {

    @Test
    void computeReturnsConsistentHash() {
        // Same machine → same fingerprint on repeated calls
        String fp1 = DeviceFingerprint.compute();
        String fp2 = DeviceFingerprint.compute();
        assertNotNull(fp1);
        assertFalse(fp1.isBlank());
        assertEquals(fp1, fp2, "Fingerprint must be deterministic");
    }

    @Test
    void computeReturnsHexSha256() {
        String fp = DeviceFingerprint.compute();
        // SHA-256 hex = 64 chars
        assertEquals(64, fp.length(), "SHA-256 hex should be 64 chars");
        assertTrue(fp.matches("[0-9a-f]{64}"), "Should be lowercase hex");
    }

    @Test
    void fromRawComponentsProducesDeterministicHash() {
        String fp1 = DeviceFingerprint.fromComponents("TestCPU", 8, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0-generic");
        String fp2 = DeviceFingerprint.fromComponents("TestCPU", 8, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0-generic");
        assertEquals(fp1, fp2);
    }

    @Test
    void differentComponentsProduceDifferentHash() {
        String fp1 = DeviceFingerprint.fromComponents("TestCPU", 8, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0");
        String fp2 = DeviceFingerprint.fromComponents("TestCPU", 16, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0");
        assertNotEquals(fp1, fp2, "Different core count should produce different fingerprint");
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.DeviceFingerprintTest" -i 2>&1 | tail -20`
Expected: FAIL — class does not exist

**Step 3: Write minimal implementation**

```java
package com.localcloud.licensing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Computes a stable device fingerprint from hardware signals.
 * The fingerprint is a SHA-256 hex string derived from CPU, RAM, MAC address,
 * disk serial, and kernel version. Same physical machine always produces
 * the same fingerprint regardless of container rebuilds or volume deletion.
 */
public final class DeviceFingerprint {

    private DeviceFingerprint() {}

    /**
     * Compute device fingerprint from live system hardware signals.
     * Falls back gracefully if any signal is unavailable.
     */
    public static String compute() {
        String cpuModel = readCpuModel();
        int cores = Runtime.getRuntime().availableProcessors();
        long ramMb = readTotalRamMb();
        String mac = readPrimaryMac();
        String diskSerial = readDiskSerial();
        String kernel = System.getProperty("os.version", "unknown");
        return fromComponents(cpuModel, cores, ramMb, mac, diskSerial, kernel);
    }

    /**
     * Compute fingerprint from explicit components (for testing).
     */
    public static String fromComponents(String cpuModel, int cores, long ramMb,
                                         String mac, String diskSerial, String kernel) {
        String raw = cpuModel + ":" + cores + ":" + ramMb + ":" + mac + ":" + diskSerial + ":" + kernel;
        return sha256Hex(raw);
    }

    private static String readCpuModel() {
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                    .filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst()
                    .orElse("unknown-cpu");
        } catch (IOException e) {
            return "unknown-cpu";
        }
    }

    private static long readTotalRamMb() {
        try {
            return Files.readAllLines(Path.of("/proc/meminfo")).stream()
                    .filter(line -> line.startsWith("MemTotal"))
                    .map(line -> {
                        String[] parts = line.split("\\s+");
                        return Long.parseLong(parts[1]) / 1024; // kB to MB
                    })
                    .findFirst()
                    .orElse(0L);
        } catch (IOException e) {
            return Runtime.getRuntime().totalMemory() / (1024 * 1024);
        }
    }

    private static String readPrimaryMac() {
        try {
            Path netDir = Path.of("/sys/class/net");
            if (!Files.isDirectory(netDir)) return "no-mac";
            try (Stream<Path> dirs = Files.list(netDir)) {
                return dirs
                        .filter(p -> !p.getFileName().toString().equals("lo"))
                        .map(p -> {
                            try {
                                return Files.readString(p.resolve("address")).trim();
                            } catch (IOException e) {
                                return "";
                            }
                        })
                        .filter(addr -> !addr.isBlank() && !addr.equals("00:00:00:00:00:00"))
                        .findFirst()
                        .orElse("no-mac");
            }
        } catch (IOException e) {
            return "no-mac";
        }
    }

    private static String readDiskSerial() {
        try {
            Path blockDir = Path.of("/sys/block");
            if (!Files.isDirectory(blockDir)) return "no-serial";
            try (Stream<Path> dirs = Files.list(blockDir)) {
                return dirs
                        .map(p -> {
                            try {
                                Path serial = p.resolve("serial");
                                if (Files.exists(serial)) {
                                    return Files.readString(serial).trim();
                                }
                                return "";
                            } catch (IOException e) {
                                return "";
                            }
                        })
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .orElse("no-serial");
            }
        } catch (IOException e) {
            return "no-serial";
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.DeviceFingerprintTest" -i 2>&1 | tail -20`
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/DeviceFingerprint.java \
        localcloud-server/src/test/java/com/localcloud/licensing/DeviceFingerprintTest.java
git commit -m "feat(licensing): add DeviceFingerprint hardware signal collector"
```

---

### Task 2: LicenseTier — Tier Enum with Service Gating

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/licensing/LicenseTier.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/LicenseTierTest.java`

**Step 1: Write the failing test**

```java
package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LicenseTierTest {

    @Test
    void communityAllowsOnlyThreeServices() {
        assertTrue(LicenseTier.COMMUNITY.isServiceAllowed("gcs"));
        assertTrue(LicenseTier.COMMUNITY.isServiceAllowed("pubsub"));
        assertTrue(LicenseTier.COMMUNITY.isServiceAllowed("firestore"));
        assertFalse(LicenseTier.COMMUNITY.isServiceAllowed("spanner"));
        assertFalse(LicenseTier.COMMUNITY.isServiceAllowed("bigquery"));
        assertFalse(LicenseTier.COMMUNITY.isServiceAllowed("memorystore"));
    }

    @Test
    void trialAllowsAllServices() {
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("spanner"));
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("bigquery"));
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("gcs"));
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("workflows"));
    }

    @Test
    void proAllowsAllServices() {
        assertTrue(LicenseTier.PRO.isServiceAllowed("spanner"));
        assertTrue(LicenseTier.PRO.isServiceAllowed("bigtable"));
        assertTrue(LicenseTier.PRO.isServiceAllowed("secretmanager"));
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertEquals(LicenseTier.PRO, LicenseTier.fromString("pro"));
        assertEquals(LicenseTier.PRO, LicenseTier.fromString("PRO"));
        assertEquals(LicenseTier.PRO, LicenseTier.fromString("Pro"));
    }

    @Test
    void fromStringDefaultsToCommunityForUnknown() {
        assertEquals(LicenseTier.COMMUNITY, LicenseTier.fromString("invalid"));
        assertEquals(LicenseTier.COMMUNITY, LicenseTier.fromString(null));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseTierTest" -i 2>&1 | tail -20`
Expected: FAIL — class does not exist

**Step 3: Write minimal implementation**

```java
package com.localcloud.licensing;

import java.util.Set;

/**
 * License tiers that control which emulator services are available.
 */
public enum LicenseTier {
    COMMUNITY(Set.of("gcs", "pubsub", "firestore")),
    TRIAL(Set.of()),      // empty = all allowed
    PRO(Set.of()),
    TEAM(Set.of()),
    ENTERPRISE(Set.of());

    private final Set<String> allowedServices;

    LicenseTier(Set<String> allowedServices) {
        this.allowedServices = allowedServices;
    }

    /**
     * Check if a service is allowed under this tier.
     * Empty allowedServices set means ALL services are allowed.
     */
    public boolean isServiceAllowed(String serviceName) {
        if (allowedServices.isEmpty()) return true;
        return allowedServices.contains(serviceName.toLowerCase());
    }

    /**
     * Get the set of allowed services. Empty means all.
     */
    public Set<String> getAllowedServices() {
        return allowedServices;
    }

    /**
     * Parse tier from string, case-insensitive. Defaults to COMMUNITY for unknown values.
     */
    public static LicenseTier fromString(String tier) {
        if (tier == null || tier.isBlank()) return COMMUNITY;
        try {
            return valueOf(tier.toUpperCase());
        } catch (IllegalArgumentException e) {
            return COMMUNITY;
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseTierTest" -i 2>&1 | tail -20`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/LicenseTier.java \
        localcloud-server/src/test/java/com/localcloud/licensing/LicenseTierTest.java
git commit -m "feat(licensing): add LicenseTier enum with service gating"
```

---

### Task 3: OfflineKeyValidator — Ed25519 Signature Verification

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/licensing/OfflineKeyValidator.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/OfflineKeyValidatorTest.java`

This is the core crypto. Ed25519 keypair: private key stays on license server (or CLI tool), public key embedded in this class. Offline keys are `lck_<base64url(version + json_payload + ed25519_signature)>`.

**Step 1: Write the failing test**

```java
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
        // Generate a test keypair (in production, the public key is embedded)
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
        // Tamper: change one character in the base64 payload portion
        String tampered = key.substring(0, 5) + "X" + key.substring(6);
        LicenseResult result = validator.validate(tampered, null);
        assertFalse(result.isValid());
    }

    @Test
    void wrongPublicKeyRejectsKey() throws Exception {
        // Sign with test keypair but validate with a different public key
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
        // No device_id in payload → floating license
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

    /**
     * Helper: create a signed offline key for testing.
     * Format: lck_<base64url(0x01 + payload_bytes + 64-byte-signature)>
     */
    private static String generateTestKey(String jsonPayload, PrivateKey privateKey) throws Exception {
        byte[] payloadBytes = jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payloadBytes);
        byte[] signature = sig.sign();

        // version(1) + payload(N) + signature(64)
        byte[] combined = new byte[1 + payloadBytes.length + signature.length];
        combined[0] = 0x01; // version
        System.arraycopy(payloadBytes, 0, combined, 1, payloadBytes.length);
        System.arraycopy(signature, 0, combined, 1 + payloadBytes.length, signature.length);

        return "lck_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OfflineKeyValidatorTest" -i 2>&1 | tail -20`
Expected: FAIL — classes do not exist

**Step 3a: Create LicenseResult record**

Create: `localcloud-server/src/main/java/com/localcloud/licensing/LicenseResult.java`

```java
package com.localcloud.licensing;

/**
 * Result of a license validation attempt.
 */
public record LicenseResult(
        boolean isValid,
        LicenseTier tier,
        String email,
        String deviceId,
        long expiresEpoch,
        String errorMessage
) {
    public static LicenseResult valid(LicenseTier tier, String email, String deviceId, long expiresEpoch) {
        return new LicenseResult(true, tier, email, deviceId, expiresEpoch, null);
    }

    public static LicenseResult invalid(String errorMessage) {
        return new LicenseResult(false, null, null, null, 0, errorMessage);
    }
}
```

**Step 3b: Create OfflineKeyValidator**

```java
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

        try {
            // Decode base64url payload
            String encoded = key.substring(PREFIX.length());
            byte[] combined = Base64.getUrlDecoder().decode(encoded);

            if (combined.length < 1 + SIGNATURE_LENGTH + 2) {
                return LicenseResult.invalid("Key too short");
            }

            // Check version
            int version = combined[0] & 0xFF;
            if (version != VERSION) {
                return LicenseResult.invalid("Unsupported key version: " + version);
            }

            // Split: payload = bytes[1..N-64], signature = last 64 bytes
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
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OfflineKeyValidatorTest" -i 2>&1 | tail -20`
Expected: PASS (8 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/LicenseResult.java \
        localcloud-server/src/main/java/com/localcloud/licensing/OfflineKeyValidator.java \
        localcloud-server/src/test/java/com/localcloud/licensing/OfflineKeyValidatorTest.java
git commit -m "feat(licensing): add Ed25519 offline key validator with device binding"
```

---

### Task 4: OnlineKeyValidator — HTTP License Server Client

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/licensing/OnlineKeyValidator.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/OnlineKeyValidatorTest.java`

For Phase 1, online validation calls the license server URL. If `LOCALCLOUD_LICENSE_SERVER` is not set or set to `none`, online keys are accepted as PRO (development bypass). This lets the system work before the license server exists.

**Step 1: Write the failing test**

```java
package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnlineKeyValidatorTest {

    @Test
    void bypassModeAcceptsAnyOnlineKey() {
        // When license server URL is "none" → bypass mode for development
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_testkey123", "device-id");
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void rejectsNonOnlinePrefix() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lck_someofflinekey", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("prefix"));
    }

    @Test
    void rejectsNullKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate(null, "device-id");
        assertFalse(result.isValid());
    }

    @Test
    void rejectsEmptyKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_", "device-id");
        assertFalse(result.isValid());
    }

    @Test
    void unreachableServerReturnsError() {
        // Point to a non-existent server
        OnlineKeyValidator validator = new OnlineKeyValidator("http://localhost:19999");
        LicenseResult result = validator.validate("lco_testkey123", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("unreachable") || result.errorMessage().contains("connect"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OnlineKeyValidatorTest" -i 2>&1 | tail -20`
Expected: FAIL — class does not exist

**Step 3: Write minimal implementation**

```java
package com.localcloud.licensing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Validates online license keys (lco_ prefix) against the license server.
 * When the license server URL is "none", operates in bypass mode (all keys accepted as PRO).
 */
public class OnlineKeyValidator {

    private static final Logger logger = LoggerFactory.getLogger(OnlineKeyValidator.class);
    private static final String PREFIX = "lco_";

    private final String licenseServerUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public OnlineKeyValidator(String licenseServerUrl) {
        this.licenseServerUrl = licenseServerUrl != null ? licenseServerUrl : "none";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Validate an online key against the license server.
     *
     * @param key      the full key string (including lco_ prefix)
     * @param deviceId the current device fingerprint
     * @return validation result
     */
    public LicenseResult validate(String key, String deviceId) {
        if (key == null || !key.startsWith(PREFIX)) {
            return LicenseResult.invalid("Invalid key prefix — expected 'lco_'");
        }

        String keyBody = key.substring(PREFIX.length());
        if (keyBody.isBlank()) {
            return LicenseResult.invalid("Empty key value after prefix");
        }

        // Bypass mode: no license server configured
        if ("none".equalsIgnoreCase(licenseServerUrl)) {
            logger.info("License server bypass mode — accepting key as PRO");
            return LicenseResult.valid(LicenseTier.PRO, "bypass@localcloud.dev", deviceId,
                    Long.MAX_VALUE);
        }

        // Call license server
        try {
            String body = mapper.writeValueAsString(
                    java.util.Map.of("key", key, "device_id", deviceId != null ? deviceId : ""));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(licenseServerUrl + "/license/validate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                String tier = json.path("tier").asText("community");
                String email = json.path("email").asText("unknown");
                long expires = json.path("expires").asLong(0);
                return LicenseResult.valid(LicenseTier.fromString(tier), email, deviceId, expires);
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                JsonNode json = mapper.readTree(response.body());
                String msg = json.path("message").asText("Invalid or revoked key");
                return LicenseResult.invalid(msg);
            } else {
                return LicenseResult.invalid("License server returned HTTP " + response.statusCode());
            }

        } catch (java.net.ConnectException e) {
            return LicenseResult.invalid("License server unreachable at " + licenseServerUrl);
        } catch (Exception e) {
            logger.debug("Online key validation failed: {}", e.getMessage());
            return LicenseResult.invalid("License server unreachable — " + e.getMessage());
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OnlineKeyValidatorTest" -i 2>&1 | tail -20`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/OnlineKeyValidator.java \
        localcloud-server/src/test/java/com/localcloud/licensing/OnlineKeyValidatorTest.java
git commit -m "feat(licensing): add online key validator with server bypass mode"
```

---

### Task 5: LicenseCache — Tamper-Resistant Cached License State

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/licensing/LicenseCache.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/LicenseCacheTest.java`

Stores validated license result to disk in HMAC-signed binary format. Used for offline grace period (online keys) and clock-tamper detection (offline keys).

**Step 1: Write the failing test**

```java
package com.localcloud.licensing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LicenseCacheTest {

    @TempDir
    Path tempDir;
    private LicenseCache cache;

    @BeforeEach
    void setUp() {
        cache = new LicenseCache(tempDir, "test-device-fingerprint");
    }

    @Test
    void writeAndReadRoundTrip() {
        LicenseResult original = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(original);

        LicenseResult loaded = cache.read();
        assertNotNull(loaded);
        assertTrue(loaded.isValid());
        assertEquals(LicenseTier.PRO, loaded.tier());
        assertEquals("test@example.com", loaded.email());
    }

    @Test
    void tamperedFileReturnsNull() {
        LicenseResult original = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(original);

        // Tamper with the cache file
        Path cacheFile = tempDir.resolve(".license").resolve("token.bin");
        try {
            byte[] bytes = Files.readAllBytes(cacheFile);
            bytes[10] ^= 0xFF; // flip some bits
            Files.write(cacheFile, bytes);
        } catch (Exception e) {
            fail("Could not tamper with file: " + e);
        }

        LicenseResult loaded = cache.read();
        assertNull(loaded, "Tampered cache should return null");
    }

    @Test
    void missingFileReturnsNull() {
        LicenseResult loaded = cache.read();
        assertNull(loaded);
    }

    @Test
    void differentDeviceFingerprintCannotReadCache() {
        LicenseResult original = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(original);

        // Try reading with a different device fingerprint
        LicenseCache otherDeviceCache = new LicenseCache(tempDir, "different-device");
        LicenseResult loaded = otherDeviceCache.read();
        assertNull(loaded, "Different device fingerprint should not be able to read cache");
    }

    @Test
    void bootCounterIncrementsOnEachWrite() {
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(result);
        long count1 = cache.getBootCount();

        cache.write(result);
        long count2 = cache.getBootCount();

        assertEquals(count1 + 1, count2, "Boot counter should increment");
    }

    @Test
    void lastSeenTimestampIsRecorded() {
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        long before = System.currentTimeMillis() / 1000;
        cache.write(result);
        long after = System.currentTimeMillis() / 1000;

        long lastSeen = cache.getLastSeenTimestamp();
        assertTrue(lastSeen >= before && lastSeen <= after, "Last seen should be within write window");
    }

    @Test
    void graceWindowCheck() {
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(result);
        // Just written — should be within any grace window
        assertTrue(cache.isWithinGracePeriod(72), "Freshly written cache should be within grace period");
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseCacheTest" -i 2>&1 | tail -20`
Expected: FAIL — class does not exist

**Step 3: Write minimal implementation**

```java
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
    // Embedded secret — combined with device fingerprint to derive HMAC key
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

            // Read existing boot counter
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

            // Split data and HMAC
            byte[] data = new byte[allBytes.length - HMAC_LENGTH];
            byte[] storedHmac = new byte[HMAC_LENGTH];
            System.arraycopy(allBytes, 0, data, 0, data.length);
            System.arraycopy(allBytes, data.length, storedHmac, 0, HMAC_LENGTH);

            // Verify HMAC
            byte[] computedHmac = computeHmac(data);
            if (!MessageDigest.isEqual(computedHmac, storedHmac)) {
                logger.warn("License cache HMAC mismatch — file tampered or wrong device");
                return null;
            }

            // Parse
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

    // Jackson-friendly record for serialization
    public record CachedLicense(String tier, String email, String deviceId, long expiresEpoch) {
        @SuppressWarnings("unused") // Jackson needs default constructor
        public CachedLicense() { this("community", "", "", 0); }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseCacheTest" -i 2>&1 | tail -20`
Expected: PASS (8 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/LicenseCache.java \
        localcloud-server/src/test/java/com/localcloud/licensing/LicenseCacheTest.java
git commit -m "feat(licensing): add tamper-resistant HMAC-signed license cache"
```

---

### Task 6: LicenseManager — Orchestrator Combining All Validators

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/licensing/LicenseManager.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/LicenseManagerTest.java`

Single entry point that routes to offline or online validator, manages cache, and returns the final tier.

**Step 1: Write the failing test**

```java
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
        // No API key + license server = "none" → bypass mode (dev/testing)
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
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseManagerTest" -i 2>&1 | tail -20`
Expected: FAIL — class does not exist

**Step 3: Write minimal implementation**

```java
package com.localcloud.licensing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.PublicKey;

/**
 * Orchestrates license validation.
 *
 * Routes to OfflineKeyValidator (lck_ prefix) or OnlineKeyValidator (lco_ prefix),
 * manages the license cache for offline grace periods, and returns the final tier.
 *
 * When no API key is set AND license server is "none", operates in bypass mode
 * (all services unlocked as PRO). This is the default for development/testing.
 */
public class LicenseManager {

    private static final Logger logger = LoggerFactory.getLogger(LicenseManager.class);
    private static final int GRACE_HOURS = 72;

    private final String apiKey;
    private final String deviceId;
    private final OfflineKeyValidator offlineValidator;
    private final OnlineKeyValidator onlineValidator;
    private final LicenseCache cache;
    private final boolean bypassMode;

    public LicenseManager(String apiKey, String licenseServerUrl, Path dataDir, PublicKey offlinePublicKey) {
        this.apiKey = apiKey;
        this.deviceId = DeviceFingerprint.compute();
        this.offlineValidator = new OfflineKeyValidator(offlinePublicKey);
        this.onlineValidator = new OnlineKeyValidator(licenseServerUrl);
        this.cache = new LicenseCache(dataDir, deviceId);

        // Bypass mode: no key AND no server configured
        this.bypassMode = (apiKey == null || apiKey.isBlank())
                && ("none".equalsIgnoreCase(licenseServerUrl) || licenseServerUrl == null || licenseServerUrl.isBlank());
    }

    /**
     * Validate the license and return the result.
     * This is the single entry point — call once at startup.
     */
    public LicenseResult validate() {
        if (bypassMode) {
            logger.info("License bypass mode — no API key required (development mode)");
            LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "dev@localcloud.dev", deviceId, Long.MAX_VALUE);
            cache.write(result);
            return result;
        }

        if (apiKey == null || apiKey.isBlank()) {
            // No key, server is configured → check cache for grace period
            return checkCacheGrace("No API key provided. Set LOCALCLOUD_API_KEY or get a key at https://localcloud.dev");
        }

        LicenseResult result;

        if (apiKey.startsWith("lck_")) {
            // Offline key
            result = offlineValidator.validate(apiKey, deviceId);
        } else if (apiKey.startsWith("lco_")) {
            // Online key
            result = onlineValidator.validate(apiKey, deviceId);
            if (!result.isValid() && result.errorMessage() != null
                    && result.errorMessage().contains("unreachable")) {
                // Server unreachable — fall back to cache
                return checkCacheGrace(result.errorMessage());
            }
        } else {
            result = LicenseResult.invalid(
                    "Unknown key format. Keys must start with 'lco_' (online) or 'lck_' (offline)");
        }

        if (result.isValid()) {
            cache.write(result);
            logger.info("License valid — tier={}, email={}", result.tier(), result.email());
        } else {
            logger.warn("License validation failed: {}", result.errorMessage());
        }

        return result;
    }

    /**
     * Get the computed device fingerprint.
     */
    public String getDeviceId() {
        return deviceId;
    }

    private LicenseResult checkCacheGrace(String originalError) {
        LicenseResult cached = cache.read();
        if (cached != null && cached.isValid() && cache.isWithinGracePeriod(GRACE_HOURS)) {
            logger.info("Using cached license (grace period) — tier={}", cached.tier());
            cache.write(cached); // update last-seen timestamp
            return cached;
        }
        return LicenseResult.invalid(originalError);
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseManagerTest" -i 2>&1 | tail -20`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/LicenseManager.java \
        localcloud-server/src/test/java/com/localcloud/licensing/LicenseManagerTest.java
git commit -m "feat(licensing): add LicenseManager orchestrator with cache grace period"
```

---

### Task 7: KeyGenerator CLI — Ed25519 Keypair and Offline Key Generator

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/licensing/KeyGenerator.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/KeyGeneratorTest.java`

Standalone CLI tool for generating the Ed25519 keypair (one-time) and issuing offline keys. Run as `java -cp server.jar com.localcloud.licensing.KeyGenerator`.

**Step 1: Write the failing test**

```java
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
        // Validate with OfflineKeyValidator
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
        // Correct device
        LicenseResult result = validator.validate(key, "abc123");
        assertTrue(result.isValid());
        // Wrong device
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
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.KeyGeneratorTest" -i 2>&1 | tail -20`
Expected: FAIL — class does not exist

**Step 3: Write minimal implementation**

```java
package com.localcloud.licensing;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CLI tool and utility for generating Ed25519 keypairs and license keys.
 *
 * Usage as CLI:
 *   java -cp server.jar com.localcloud.licensing.KeyGenerator keypair
 *   java -cp server.jar com.localcloud.licensing.KeyGenerator offline \
 *       --private-key <base64> --email user@co.com --tier pro --days 365
 *   java -cp server.jar com.localcloud.licensing.KeyGenerator online
 */
public final class KeyGenerator {

    private static final ObjectMapper mapper = new ObjectMapper();

    private KeyGenerator() {}

    /**
     * Generate a new Ed25519 keypair for signing offline keys.
     */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        return kpg.generateKeyPair();
    }

    /**
     * Generate a signed offline license key.
     *
     * @param privateKey the Ed25519 private key
     * @param email      user email
     * @param tier       license tier (pro, team, enterprise)
     * @param deviceId   optional device fingerprint for device-bound keys (null for floating)
     * @param days       validity in days from now
     * @return the full lck_ prefixed key string
     */
    public static String generateOfflineKey(PrivateKey privateKey, String email, String tier,
                                             String deviceId, int days) throws Exception {
        long now = Instant.now().getEpochSecond();
        long expires = Instant.now().plus(days, ChronoUnit.DAYS).getEpochSecond();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("email", email);
        claims.put("tier", tier);
        claims.put("issued", now);
        claims.put("expires", expires);
        claims.put("offline", true);
        if (deviceId != null && !deviceId.isBlank()) {
            claims.put("device_id", deviceId);
        }

        byte[] payloadBytes = mapper.writeValueAsBytes(claims);

        // Sign payload
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payloadBytes);
        byte[] signature = sig.sign();

        // Combine: version(1) + payload(N) + signature(64)
        byte[] combined = new byte[1 + payloadBytes.length + signature.length];
        combined[0] = 0x01;
        System.arraycopy(payloadBytes, 0, combined, 1, payloadBytes.length);
        System.arraycopy(signature, 0, combined, 1 + payloadBytes.length, signature.length);

        return "lck_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    /**
     * Generate a random online key (for the license server to store).
     */
    public static String generateOnlineKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return "lco_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /**
     * Encode a public key to base64 for embedding or config.
     */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getEncoded());
    }

    /**
     * Decode a public key from base64.
     */
    public static PublicKey decodePublicKey(String encoded) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getUrlDecoder().decode(encoded);
        KeyFactory kf = KeyFactory.getInstance("EdDSA");
        return kf.generatePublic(new java.security.spec.X509EncodedKeySpec(keyBytes));
    }

    /**
     * CLI entry point.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage:");
            System.out.println("  keypair                          Generate Ed25519 keypair");
            System.out.println("  online                           Generate random online key");
            System.out.println("  offline --private-key <b64>      Generate signed offline key");
            System.out.println("         --email <email>");
            System.out.println("         --tier <pro|team|enterprise>");
            System.out.println("         [--device-id <fingerprint>]");
            System.out.println("         [--days <validity-days>]");
            return;
        }

        switch (args[0]) {
            case "keypair" -> {
                KeyPair kp = generateKeyPair();
                System.out.println("PRIVATE_KEY=" + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(kp.getPrivate().getEncoded()));
                System.out.println("PUBLIC_KEY=" + encodePublicKey(kp.getPublic()));
                System.out.println("\nEmbed PUBLIC_KEY in LicenseManager.java");
                System.out.println("Keep PRIVATE_KEY secret on your license server");
            }
            case "online" -> {
                System.out.println(generateOnlineKey());
            }
            case "offline" -> {
                String privateKeyB64 = getArg(args, "--private-key");
                String email = getArg(args, "--email");
                String tier = getArgOr(args, "--tier", "pro");
                String deviceId = getArgOr(args, "--device-id", null);
                int days = Integer.parseInt(getArgOr(args, "--days", "365"));

                byte[] pkBytes = Base64.getUrlDecoder().decode(privateKeyB64);
                KeyFactory kf = KeyFactory.getInstance("EdDSA");
                PrivateKey pk = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkBytes));

                String key = generateOfflineKey(pk, email, tier, deviceId, days);
                System.out.println(key);
            }
            default -> System.err.println("Unknown command: " + args[0]);
        }
    }

    private static String getArg(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        throw new IllegalArgumentException("Missing required argument: " + name);
    }

    private static String getArgOr(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return defaultValue;
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.KeyGeneratorTest" -i 2>&1 | tail -20`
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/KeyGenerator.java \
        localcloud-server/src/test/java/com/localcloud/licensing/KeyGeneratorTest.java
git commit -m "feat(licensing): add Ed25519 keypair and key generation CLI tool"
```

---

### Task 8: Integrate LicenseManager into LocalCloudApplication Startup

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

Wire LicenseManager into the application startup. Read `LOCALCLOUD_API_KEY` and `LOCALCLOUD_LICENSE_SERVER` env vars. On invalid license, log error and exit. On valid license, apply tier-based service gating.

**Step 1: Add config fields for licensing**

Add to `LocalCloudConfig.java` — new fields and env var reads:

```java
// In fields section:
private String apiKey;
private String licenseServerUrl;

// In fromEnvironment() method:
config.apiKey = System.getenv().getOrDefault("LOCALCLOUD_API_KEY", "");
config.licenseServerUrl = System.getenv().getOrDefault("LOCALCLOUD_LICENSE_SERVER", "none");

// Add getters:
public String getApiKey() { return apiKey; }
public String getLicenseServerUrl() { return licenseServerUrl; }
```

**Step 2: Integrate into LocalCloudApplication.start()**

Add after config loading, before server builder (approximately after line 143):

```java
// --- License validation ---
// For Phase 1: public key is null (bypass mode uses online validator which accepts "none" server)
// In Phase 2, embed the production Ed25519 public key here
java.security.PublicKey licensePublicKey = null;
try {
    String pubKeyEnv = System.getenv("LOCALCLOUD_LICENSE_PUBLIC_KEY");
    if (pubKeyEnv != null && !pubKeyEnv.isBlank()) {
        licensePublicKey = com.localcloud.licensing.KeyGenerator.decodePublicKey(pubKeyEnv);
    }
} catch (Exception e) {
    logger.warn("Failed to load license public key: {}", e.getMessage());
}

var licenseManager = new com.localcloud.licensing.LicenseManager(
        config.getApiKey().isBlank() ? null : config.getApiKey(),
        config.getLicenseServerUrl(),
        config.getDataDir(),
        licensePublicKey);

com.localcloud.licensing.LicenseResult licenseResult = licenseManager.validate();

if (!licenseResult.isValid()) {
    logger.error("=== LICENSE VALIDATION FAILED ===");
    logger.error(licenseResult.errorMessage());
    logger.error("Set LOCALCLOUD_API_KEY or visit https://localcloud.dev to get a license key.");
    logger.error("================================");
    System.exit(1);
}

logger.info("License: tier={}, email={}, device={}", licenseResult.tier(), licenseResult.email(),
        licenseManager.getDeviceId().substring(0, 8) + "...");

// Apply tier-based service gating
if (licenseResult.tier() != null) {
    for (String serviceName : config.getServiceRegistry().getServiceNames()) {
        if (!licenseResult.tier().isServiceAllowed(serviceName)) {
            config.setServiceEnabled(serviceName, false);
            logger.info("Service '{}' disabled — not available in {} tier", serviceName, licenseResult.tier());
        }
    }
}
```

**Step 3: Run full test suite**

Run: `cd localcloud-server && ./gradlew test 2>&1 | tail -10`
Expected: PASS — existing tests should not break because default config has no API key and license server defaults to "none" (bypass mode)

**Step 4: Build shadow JAR to verify compilation**

Run: `cd localcloud-server && ./gradlew shadowJar 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java \
        localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java
git commit -m "feat(licensing): integrate LicenseManager into application startup"
```

---

### Task 9: Entrypoint License Banner

**Files:**
- Modify: `docker-entrypoint.sh`

Add a license status banner to the container startup output. Reads `LOCALCLOUD_API_KEY` and prints appropriate message. The actual validation happens in Java — this is just UX.

**Step 1: Add banner after the update check block (around line 395)**

```bash
# --- License status banner ---
echo ""
if [ -n "$LOCALCLOUD_API_KEY" ]; then
    case "$LOCALCLOUD_API_KEY" in
        lck_*) echo "  License: offline key provided" ;;
        lco_*) echo "  License: online key provided" ;;
        *)     echo "  License: key format not recognized" ;;
    esac
else
    if [ "$LOCALCLOUD_LICENSE_SERVER" = "none" ] || [ -z "$LOCALCLOUD_LICENSE_SERVER" ]; then
        echo "  License: development mode (no key required)"
    else
        echo "  License: no key provided — trial or cached license will be used"
    fi
fi
echo ""
```

**Step 2: Test locally**

Run: `docker build -t localcloud/localcloud:latest . 2>&1 | tail -5`
Then: `./stop.sh && ./start.sh`
Then: `docker logs localcloud 2>&1 | grep -i "license"`
Expected: Should see "License: development mode (no key required)"

**Step 3: Commit**

```bash
git add docker-entrypoint.sh
git commit -m "feat(licensing): add license status banner to container startup"
```

---

### Task 10: Integration Test — Full Licensing Flow

**Files:**
- Create: `localcloud-server/src/test/java/com/localcloud/licensing/LicenseIntegrationTest.java`

End-to-end test: generate keypair → generate offline key → validate → check tier gating.

**Step 1: Write the integration test**

```java
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

        // Community tier: only 3 services
        assertTrue(result.tier().isServiceAllowed("gcs"));
        assertTrue(result.tier().isServiceAllowed("pubsub"));
        assertTrue(result.tier().isServiceAllowed("firestore"));
        assertFalse(result.tier().isServiceAllowed("spanner"));
        assertFalse(result.tier().isServiceAllowed("bigquery"));
        assertFalse(result.tier().isServiceAllowed("bigtable"));
        assertFalse(result.tier().isServiceAllowed("memorystore"));
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
        // Simulate the production flow: encode pubkey to string, embed it, decode at runtime
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
```

**Step 2: Run test**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseIntegrationTest" -i 2>&1 | tail -20`
Expected: PASS (4 tests)

**Step 3: Run full test suite**

Run: `cd localcloud-server && ./gradlew test 2>&1 | tail -10`
Expected: ALL tests pass (existing + new licensing tests)

**Step 4: Commit**

```bash
git add localcloud-server/src/test/java/com/localcloud/licensing/LicenseIntegrationTest.java
git commit -m "test(licensing): add full licensing integration tests"
```

---

### Task 11: Build, Deploy, and Smoke Test

**Files:** None new — this is a verification task.

**Step 1: Full build**

```bash
cd localcloud-server && ./gradlew build
cd ../localcloud-console && npm run build
cd .. && docker build -t localcloud/localcloud:latest .
```

**Step 2: Test bypass mode (default — no key set)**

```bash
./stop.sh && ./start.sh
sleep 15
docker logs localcloud 2>&1 | grep -i "license"
curl -s http://localhost:8080/_localcloud/health | python3 -c "import sys,json; d=json.load(sys.stdin); print('Status:', d['status'])"
```
Expected: "License: development mode" in logs, health status is "healthy" or "degraded" (normal)

**Step 3: Test with an offline key**

```bash
# Generate keypair and key inside the running container's JVM
docker exec localcloud java -cp /opt/localcloud/server.jar com.localcloud.licensing.KeyGenerator keypair
# Copy the PUBLIC_KEY and PRIVATE_KEY values
# Then generate an offline key:
docker exec localcloud java -cp /opt/localcloud/server.jar com.localcloud.licensing.KeyGenerator offline \
    --private-key <PRIVATE_KEY_HERE> --email test@test.com --tier pro --days 30
```

**Step 4: Verify key works by restarting with it**

```bash
./stop.sh
# Edit start.sh or run directly:
docker run -d --name localcloud -e LOCALCLOUD_API_KEY=lck_<generated_key> ...
docker logs localcloud 2>&1 | grep -i "license"
```
Expected: "License: offline key provided" + "License: tier=PRO"

**Step 5: Commit any fixes needed, then final commit**

```bash
git add -A
git commit -m "feat(licensing): Phase 1 complete — client-side key validation"
```

---

## Summary

| Task | Component | Tests | Description |
|------|-----------|-------|-------------|
| 1 | DeviceFingerprint | 4 | Hardware signal collection + SHA-256 |
| 2 | LicenseTier | 5 | Tier enum with service gating |
| 3 | OfflineKeyValidator | 8 | Ed25519 signature verification |
| 4 | OnlineKeyValidator | 5 | HTTP license server client (with bypass) |
| 5 | LicenseCache | 8 | HMAC-signed tamper-resistant cache |
| 6 | LicenseManager | 5 | Orchestrator combining all validators |
| 7 | KeyGenerator | 5 | Ed25519 keypair + key generation CLI |
| 8 | App Integration | 0 (existing) | Wire into LocalCloudApplication startup |
| 9 | Entrypoint Banner | 0 (manual) | Docker startup UX |
| 10 | Integration Tests | 4 | End-to-end lifecycle tests |
| 11 | Smoke Test | 0 (manual) | Build + deploy + verify |
| **Total** | | **44 tests** | |

## What This Enables

After Phase 1:
- `LOCALCLOUD_API_KEY` not set + `LOCALCLOUD_LICENSE_SERVER=none` → **bypass mode** (current behavior preserved, all services unlocked)
- `LOCALCLOUD_API_KEY=lck_...` → **offline key validated** with Ed25519, tier-based service gating applied
- `LOCALCLOUD_API_KEY=lco_...` + `LOCALCLOUD_LICENSE_SERVER=none` → **bypass mode** (accepts any online key as PRO)
- `LOCALCLOUD_API_KEY=lco_...` + `LOCALCLOUD_LICENSE_SERVER=https://...` → **online validation** against server (Phase 2)
- **CLI tool** to generate keypairs and offline keys for testing/enterprise customers

## What Phase 2 Adds (Separate Plan)

- License server with user registration, email OTP, Stripe billing
- Online key validation with real server
- Trial management with device fingerprint anti-abuse
- Interactive first-run flow in docker-entrypoint.sh
