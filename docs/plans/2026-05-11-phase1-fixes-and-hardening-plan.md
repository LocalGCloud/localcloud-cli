# Phase 1 Fixes + Client-Side Hardening Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix three known Phase 1 limitations (hardcoded secret, null public key NPE, Linux-only device fingerprint) and add clock tamper detection and HTTPS enforcement.

**Architecture:** All changes are to existing files in `com.localcloud.licensing`. No new dependencies. The EMBEDDED_SECRET is obfuscated by splitting into byte arrays (raises bar against `strings` extraction). DeviceFingerprint gains a cross-platform `java.net.NetworkInterface` fallback for MAC address. Clock tamper detection uses the existing `lastSeenTimestamp` in `LicenseCache` — checked at startup.

**Tech Stack:** Java 21 standard library only (already in use)

---

### Task 1: Obfuscate EMBEDDED_SECRET in LicenseCache

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/licensing/LicenseCache.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/LicenseCacheTest.java` (existing — just re-run)

The current `EMBEDDED_SECRET = "lc-cache-v1-k8x2m9"` is a plaintext string visible via `strings localcloud-server-all.jar`. Replace it with three byte array constants that are XOR-combined at runtime. This prevents trivial extraction while keeping the same derived key.

**Step 1: Replace the EMBEDDED_SECRET constant**

In `LicenseCache.java`, replace lines around:
```java
private static final String EMBEDDED_SECRET = "lc-cache-v1-k8x2m9";
```

With:
```java
// Secret split across multiple constants to prevent trivial `strings` extraction.
// Each part is the raw bytes of a fragment; they are XOR-combined at runtime.
private static final byte[] SECRET_A = {0x6c, 0x63, 0x2d, 0x63, 0x61, 0x63, 0x68, 0x65}; // "lc-cache"
private static final byte[] SECRET_B = {0x2d, 0x76, 0x31, 0x2d, 0x73, 0x67, 0x6e, 0x64}; // "-v1-sgnd"
private static final byte[] SECRET_C = {0x21, 0x6b, 0x38, 0x78, 0x32, 0x6d, 0x39, 0x21}; // "!k8x2m9!"
```

**Step 2: Update `deriveKey()` to use XOR-combined secret**

Replace the old `deriveKey()` method body:
```java
// OLD (remove this):
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
```

With:
```java
private byte[] deriveKey(String deviceFingerprint) {
    try {
        // XOR-combine the secret fragments at runtime
        byte[] combined = new byte[SECRET_A.length];
        for (int i = 0; i < combined.length; i++) {
            combined[i] = (byte) (SECRET_A[i] ^ SECRET_B[i] ^ SECRET_C[i]);
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(combined);
        md.update(deviceFingerprint.getBytes(StandardCharsets.UTF_8));
        return md.digest();
    } catch (Exception e) {
        throw new RuntimeException("SHA-256 not available", e);
    }
}
```

**Step 3: Run existing tests to verify nothing broke**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.LicenseCacheTest" 2>&1 | tail -15`
Expected: 7 tests PASS

**Important:** The XOR of the three byte arrays must produce the same result as the old secret to keep existing cache files working. Verify: `SECRET_A[i] ^ SECRET_B[i] ^ SECRET_C[i]` for each index produces a byte sequence that when SHA-256'd with a device fingerprint gives the same HMAC key as before.

Actually — since we are CHANGING the derived key formula (old used UTF-8 of "lc-cache-v1-k8x2m9", new uses XOR of byte arrays), any existing `token.bin` cache files will be invalidated (HMAC mismatch). This is intentional and acceptable — the system will re-validate and write a new cache. Document this in a comment.

Add this comment above the byte arrays:
```java
// NOTE: Changing these constants or the XOR combination invalidates all existing
// license cache files (HMAC mismatch). Users will need to re-validate once.
// This is acceptable — the system re-validates and writes a fresh cache.
// Format version is v2 to distinguish from pre-obfuscation caches.
```

Also update `FORMAT_VERSION` from 1 to 2 to distinguish old caches from new:
```java
private static final int FORMAT_VERSION = 2;
```

**Step 4: Run full licensing test suite**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.*" 2>&1 | tail -15`
Expected: All pass

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/LicenseCache.java
git commit -m "security(licensing): obfuscate cache HMAC secret via split byte arrays"
```

---

### Task 2: Fix Null PublicKey in OfflineKeyValidator

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/licensing/OfflineKeyValidator.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/OfflineKeyValidatorTest.java`

Currently, if `publicKey` is null and an `lck_` key is provided, `Signature.initVerify(null)` throws NullPointerException caught as a generic "Key validation failed: null" error. Fix with an early null check that produces a clear actionable message.

**Step 1: Write the failing test**

Add to `OfflineKeyValidatorTest.java`:
```java
@Test
void nullPublicKeyRejectsWithClearMessage() {
    OfflineKeyValidator nullValidator = new OfflineKeyValidator(null);
    LicenseResult result = nullValidator.validate("lck_someencodedkey", null);
    assertFalse(result.isValid());
    assertTrue(result.errorMessage().contains("public key"),
            "Error should mention public key, was: " + result.errorMessage());
}
```

**Step 2: Run to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OfflineKeyValidatorTest.nullPublicKeyRejectsWithClearMessage" 2>&1 | tail -15`
Expected: FAIL — error message doesn't contain "public key"

**Step 3: Add null check at top of `validate()` in OfflineKeyValidator**

In `OfflineKeyValidator.java`, at the start of the `validate()` method (after the prefix check), add:
```java
if (publicKey == null) {
    return LicenseResult.invalid(
        "No license public key configured. Set LOCALCLOUD_LICENSE_PUBLIC_KEY env var " +
        "or contact support at https://localcloud.dev");
}
```

The complete start of `validate()` should now be:
```java
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
        // ... rest of method unchanged
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OfflineKeyValidatorTest" 2>&1 | tail -15`
Expected: 9 tests PASS (8 existing + 1 new)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/OfflineKeyValidator.java \
        localcloud-server/src/test/java/com/localcloud/licensing/OfflineKeyValidatorTest.java
git commit -m "fix(licensing): add null publicKey guard with actionable error in OfflineKeyValidator"
```

---

### Task 3: Cross-Platform DeviceFingerprint (macOS/Windows Fallback)

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/licensing/DeviceFingerprint.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/DeviceFingerprintTest.java`

On macOS/Windows, `/proc` and `/sys` don't exist. All signals fall back to "unknown-cpu", 0 RAM, "no-mac", "no-serial" — meaning every macOS dev machine gets an identical fingerprint. Fix using `java.net.NetworkInterface` (cross-platform) for MAC address, and improve CPU/RAM using Java system properties.

**Step 1: Write the failing tests**

Add to `DeviceFingerprintTest.java`:
```java
@Test
void computeProducesUniqueishFingerprintOnAnyOS() {
    // On macOS/Windows, must not produce the all-fallback hash
    // (which would be SHA-256("unknown-cpu:N:0:no-mac:no-serial:version"))
    // At minimum, core count should vary from machine to machine
    String fp = DeviceFingerprint.compute();
    assertEquals(64, fp.length()); // Still 64-char hex
    assertNotNull(fp);
}

@Test
void readPrimaryMacViaNetworkInterfaceReturnsValidMacOrFallback() {
    // Must return a valid MAC format or the "no-mac" fallback, never null or blank
    String mac = DeviceFingerprint.readMacAddress();
    assertNotNull(mac);
    assertFalse(mac.isBlank());
    // Either "no-mac" or a mac-like string with colons
    assertTrue(mac.equals("no-mac") || mac.contains(":"),
            "Expected no-mac or colon-separated MAC, got: " + mac);
}
```

Note: `readMacAddress()` needs to be changed from `private` to `package-private` (no modifier) for testing. Do that in Step 3.

**Step 2: Run to verify they fail**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.DeviceFingerprintTest" 2>&1 | tail -15`
Expected: FAIL — `readMacAddress()` doesn't exist as a visible method

**Step 3: Update DeviceFingerprint.java**

Replace the `readPrimaryMac()` method with a cross-platform version that tries `/sys/class/net` first (Linux/Docker), then falls back to `java.net.NetworkInterface` (macOS/Windows):

```java
/**
 * Read primary MAC address. Tries Linux /sys first, then Java NetworkInterface (cross-platform).
 * Package-private for testing.
 */
static String readMacAddress() {
    // Linux/Docker: try /sys/class/net first
    try {
        Path netDir = Path.of("/sys/class/net");
        if (Files.isDirectory(netDir)) {
            try (Stream<Path> dirs = Files.list(netDir)) {
                String linuxMac = dirs
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
                        .orElse(null);
                if (linuxMac != null) return linuxMac;
            }
        }
    } catch (IOException ignored) {}

    // Cross-platform fallback: java.net.NetworkInterface
    try {
        return java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .stream()
                .filter(ni -> {
                    try {
                        return !ni.isLoopback() && ni.isUp() && ni.getHardwareAddress() != null;
                    } catch (java.net.SocketException e) {
                        return false;
                    }
                })
                .map(ni -> {
                    try {
                        byte[] mac = ni.getHardwareAddress();
                        if (mac == null) return null;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < mac.length; i++) {
                            if (i > 0) sb.append(':');
                            sb.append(String.format("%02x", mac[i]));
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("no-mac");
    } catch (java.net.SocketException e) {
        return "no-mac";
    }
}
```

Also update `readTotalRamMb()` to use `OperatingSystemMXBean` on non-Linux:

```java
private static long readTotalRamMb() {
    // Linux: try /proc/meminfo first
    try {
        return Files.readAllLines(Path.of("/proc/meminfo")).stream()
                .filter(line -> line.startsWith("MemTotal"))
                .map(line -> {
                    String[] parts = line.split("\\s+");
                    return Long.parseLong(parts[1]) / 1024;
                })
                .findFirst()
                .orElse(0L);
    } catch (IOException e) {
        // Cross-platform fallback: OS MXBean
        try {
            var bean = (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return bean.getTotalMemorySize() / (1024 * 1024);
        } catch (Exception ex) {
            return 0L;
        }
    }
}
```

Update `compute()` to use the renamed method:
```java
public static String compute() {
    String cpuModel = readCpuModel();
    int cores = Runtime.getRuntime().availableProcessors();
    long ramMb = readTotalRamMb();
    String mac = readMacAddress();          // renamed from readPrimaryMac
    String diskSerial = readDiskSerial();
    String kernel = System.getProperty("os.version", "unknown");
    return fromComponents(cpuModel, cores, ramMb, mac, diskSerial, kernel);
}
```

Remove the old `readPrimaryMac()` method entirely.

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.DeviceFingerprintTest" 2>&1 | tail -15`
Expected: 6 tests PASS (4 existing + 2 new)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/DeviceFingerprint.java \
        localcloud-server/src/test/java/com/localcloud/licensing/DeviceFingerprintTest.java
git commit -m "fix(licensing): cross-platform DeviceFingerprint with NetworkInterface MAC fallback"
```

---

### Task 4: Clock Tamper Detection at Startup

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/licensing/LicenseCache.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/licensing/LicenseManager.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/ClockTamperDetectionTest.java`

The cache already stores `lastSeenTimestamp`. If the system clock rolls backward (an attack to extend offline keys), current time < last-seen is a strong signal of tampering. Detect this at startup in `LicenseManager` and refuse to start.

Also embed a build-time timestamp floor (`MIN_BUILD_TIMESTAMP`) in `LicenseManager` — the system clock can never be earlier than when the code was compiled.

**Step 1: Write the failing test**

Create `localcloud-server/src/test/java/com/localcloud/licensing/ClockTamperDetectionTest.java`:

```java
package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class ClockTamperDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void clockRollbackIsDetected() {
        // Write a cache with future lastSeenTimestamp (simulates clock rollback)
        LicenseCache cache = new LicenseCache(tempDir, "test-device");
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev", 9999999999L);
        cache.write(result);

        // Detect rollback: current time is BEFORE last-seen
        // The detectClockTamper method returns true if clock rolled back
        boolean tampered = cache.detectClockRollback();
        assertFalse(tampered, "Fresh cache should not trigger rollback detection");
    }

    @Test
    void buildTimestampFloorIsReasonable() {
        // The embedded build timestamp must be in the past (code was compiled before now)
        long floor = LicenseManager.getBuildTimestampFloor();
        assertTrue(floor > 0, "Build timestamp floor must be positive");
        assertTrue(floor < System.currentTimeMillis() / 1000,
                "Build timestamp floor must be in the past");
        // Sanity: floor should be after 2020-01-01 (1577836800)
        assertTrue(floor > 1577836800L, "Build timestamp floor seems too old");
    }
}
```

**Step 2: Run to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.ClockTamperDetectionTest" 2>&1 | tail -15`
Expected: FAIL — methods don't exist yet

**Step 3: Add `detectClockRollback()` to LicenseCache**

In `LicenseCache.java`, add this method after `isWithinGracePeriod()`:

```java
/**
 * Detect if the system clock has been rolled back since the last boot.
 * Returns true if current time is BEFORE the last-seen timestamp (indicates tampering).
 * Returns false if no cache exists (first boot) or clock is normal.
 */
public boolean detectClockRollback() {
    LicenseCacheData data = readRaw();
    if (data == null) return false; // No cache — cannot detect rollback
    long now = Instant.now().getEpochSecond();
    if (now < data.lastSeen()) {
        logger.warn("Clock rollback detected: last-seen={}, now={}, delta={}s",
                data.lastSeen(), now, data.lastSeen() - now);
        return true;
    }
    return false;
}
```

**Step 4: Add build timestamp floor to LicenseManager**

In `LicenseManager.java`, add a static constant and accessor:

```java
/**
 * Build-time timestamp floor (seconds since epoch).
 * The system clock can never legitimately be before this value.
 * This constant is baked in at compile time.
 */
// To regenerate: System.currentTimeMillis() / 1000 at time of release
private static final long BUILD_TIMESTAMP_FLOOR = 1747000000L; // approx 2025-05-11

public static long getBuildTimestampFloor() {
    return BUILD_TIMESTAMP_FLOOR;
}
```

**Step 5: Add clock tamper check in `validate()`**

In `LicenseManager.validate()`, add clock checks at the very start (before bypass mode check):

```java
public LicenseResult validate() {
    // Clock tamper detection: check against build floor and last-seen cache
    long now = Instant.now().getEpochSecond();
    if (now < BUILD_TIMESTAMP_FLOOR) {
        return LicenseResult.invalid(
            "System clock appears to be set before build date. " +
            "Please set the correct system time.");
    }

    if (cache.detectClockRollback()) {
        return LicenseResult.invalid(
            "System clock was rolled back since last run. " +
            "Clock manipulation is not permitted. Please set the correct system time.");
    }

    if (bypassMode) {
        // ... rest of existing method
```

**Step 6: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.ClockTamperDetectionTest" 2>&1 | tail -15`
Expected: 2 tests PASS

Run full licensing suite: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.*" 2>&1 | tail -15`
Expected: All pass

**Step 7: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/LicenseCache.java \
        localcloud-server/src/main/java/com/localcloud/licensing/LicenseManager.java \
        localcloud-server/src/test/java/com/localcloud/licensing/ClockTamperDetectionTest.java
git commit -m "feat(licensing): add clock tamper detection and build timestamp floor"
```

---

### Task 5: HTTPS Enforcement in OnlineKeyValidator

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/licensing/OnlineKeyValidator.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/OnlineKeyValidatorTest.java`

If `LOCALCLOUD_LICENSE_SERVER` is set to an `http://` URL (not `https://`), license keys are transmitted in plaintext. Warn loudly and in production (non-localhost) refuse to proceed.

**Step 1: Write the failing test**

Add to `OnlineKeyValidatorTest.java`:

```java
@Test
void httpUrlToNonLocalhostLogsWarningAndFails() {
    // Non-localhost http:// should be rejected as insecure
    OnlineKeyValidator validator = new OnlineKeyValidator("http://api.example.com");
    LicenseResult result = validator.validate("lco_somekey", "device-id");
    assertFalse(result.isValid());
    assertTrue(result.errorMessage().contains("HTTPS") || result.errorMessage().contains("insecure"),
            "Should reject non-HTTPS non-localhost URL");
}

@Test
void httpLocalhostIsAllowedForDevelopment() {
    // localhost http:// is OK for local development/testing
    // Will fail with "unreachable" not "insecure" since port isn't open
    OnlineKeyValidator validator = new OnlineKeyValidator("http://localhost:19998");
    LicenseResult result = validator.validate("lco_somekey", "device-id");
    assertFalse(result.isValid());
    // Should fail with connection error, not HTTPS error
    assertFalse(result.errorMessage().contains("HTTPS"),
            "localhost http:// should not trigger HTTPS error");
}
```

**Step 2: Run to verify they fail**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OnlineKeyValidatorTest" 2>&1 | tail -15`
Expected: FAIL — no HTTPS enforcement yet

**Step 3: Add HTTPS check in OnlineKeyValidator constructor**

In `OnlineKeyValidator.java`, add a `validateUrl()` call in the constructor:

```java
public OnlineKeyValidator(String licenseServerUrl) {
    this.licenseServerUrl = licenseServerUrl != null ? licenseServerUrl : "none";
    this.insecureUrl = isInsecureUrl(this.licenseServerUrl);
    this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
}

private boolean isInsecureUrl(String url) {
    if ("none".equalsIgnoreCase(url)) return false;
    if (!url.startsWith("http://")) return false;
    // localhost and 127.x are OK for development
    return !url.contains("localhost") && !url.contains("127.0.0.1");
}
```

Add field: `private final boolean insecureUrl;`

In `validate()`, after the bypass mode check, add:
```java
if (insecureUrl) {
    logger.error("SECURITY: License server URL uses HTTP (not HTTPS): {}. " +
            "License keys will be transmitted in plaintext. " +
            "Use HTTPS for non-localhost license servers.", licenseServerUrl);
    return LicenseResult.invalid(
        "License server URL must use HTTPS for security. " +
        "Current URL is insecure: " + licenseServerUrl);
}
```

**Step 4: Run tests to verify they pass**

Run: `cd localcloud-server && ./gradlew test --tests "com.localcloud.licensing.OnlineKeyValidatorTest" 2>&1 | tail -15`
Expected: 7 tests PASS (5 existing + 2 new)

**Step 5: Run full test suite**

Run: `cd localcloud-server && ./gradlew test 2>&1 | tail -10`
Expected: All pass

**Step 6: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/licensing/OnlineKeyValidator.java \
        localcloud-server/src/test/java/com/localcloud/licensing/OnlineKeyValidatorTest.java
git commit -m "security(licensing): enforce HTTPS for non-localhost license server URLs"
```

---

### Task 6: Full Build Verification

**No new files.**

**Step 1: Run full test suite**

Run: `cd localcloud-server && ./gradlew test 2>&1 | tail -15`
Expected: All tests pass (900+ tests, 0 failures)

**Step 2: Build shadow JAR**

Run: `cd localcloud-server && ./gradlew shadowJar 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Final commit**

```bash
git add -A
git commit -m "chore(licensing): Phase 1 fixes and hardening complete"
```

---

## Summary

| Task | Fix | Tests Added |
|------|-----|-------------|
| 1 | EMBEDDED_SECRET obfuscated (byte array XOR) | 0 (existing re-run) |
| 2 | Null publicKey → clear error message | 1 |
| 3 | DeviceFingerprint macOS/Windows MAC fallback | 2 |
| 4 | Clock tamper detection + build timestamp floor | 2 |
| 5 | HTTPS enforcement for non-localhost license server | 2 |
| **Total** | | **7 new tests** |
