package com.localcloud.licensing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.PublicKey;

/**
 * Orchestrates license validation.
 *
 * Routes to OfflineKeyValidator (lck_ prefix) or OnlineKeyValidator (lco_
 * prefix),
 * manages the license cache for offline grace periods, and returns the final
 * tier.
 *
 * When ENFORCE_LICENSE is false (via /opt/localcloud/ENFORCE_LICENSE), all
 * validation
 * is skipped and PRO tier is returned immediately. This is the default for dev
 * Docker images.
 *
 * When no API key is set AND license server is "none", operates in bypass mode
 * (all services unlocked as PRO). This is the default for development/testing.
 * In production builds (BUILD_MODE file contains "production"), bypass mode is
 * disabled.
 */
public class LicenseManager {

    private static final Logger logger = LoggerFactory.getLogger(LicenseManager.class);
    private static final int GRACE_HOURS = 72;

    /**
     * Path to the BUILD_MODE file baked into the Docker image at build time.
     * Overridable via system property for testing.
     */
    static final String BUILD_MODE_PATH_PROPERTY = "localcloud.buildModePath";
    private static final String DEFAULT_BUILD_MODE_PATH = "/opt/localcloud/BUILD_MODE";

    /**
     * Path to the ENFORCE_LICENSE flag.
     * Overridable via system property for testing.
     */
    static final String ENFORCE_LICENSE_PATH_PROPERTY = "localcloud.enforceLicensePath";
    private static final String DEFAULT_ENFORCE_LICENSE_PATH = "/opt/localcloud/ENFORCE_LICENSE";

    /**
     * Returns true if this is a production build (BUILD_MODE file contains
     * "production").
     */
    public static boolean isProductionBuild() {
        String path = System.getProperty(BUILD_MODE_PATH_PROPERTY, DEFAULT_BUILD_MODE_PATH);
        try {
            String mode = Files.readString(Path.of(path)).strip();
            return "production".equalsIgnoreCase(mode);
        } catch (NoSuchFileException e) {
            return false;
        } catch (Exception e) {
            logger.warn("Could not read BUILD_MODE file at '{}': {} — defaulting to dev build",
                    path, e.getMessage());
            return false;
        }
    }

    /**
     * Returns true if license enforcement is enabled.
     * Reads /opt/localcloud/ENFORCE_LICENSE — if file is missing, defaults to true
     * (safe).
     */
    public static boolean isEnforceLicense() {
        String path = System.getProperty(ENFORCE_LICENSE_PATH_PROPERTY, DEFAULT_ENFORCE_LICENSE_PATH);
        try {
            String value = Files.readString(Path.of(path)).strip();
            return "true".equalsIgnoreCase(value);
        } catch (NoSuchFileException e) {
            return true;
        } catch (Exception e) {
            logger.warn("Could not read ENFORCE_LICENSE file at '{}': {} — defaulting to enforced",
                    path, e.getMessage());
            return true;
        }
    }

    /**
     * Build-time timestamp floor (seconds since epoch).
     * The system clock can never legitimately be before this value.
     */
    private static final long BUILD_TIMESTAMP_FLOOR = 1747000000L; // 2025-05-11

    public static long getBuildTimestampFloor() {
        return BUILD_TIMESTAMP_FLOOR;
    }

    private final String apiKey;
    private final String deviceId;
    private final OfflineKeyValidator offlineValidator;
    private final OnlineKeyValidator onlineValidator;
    private final LicenseCache cache;
    private final boolean bypassMode;
    private final boolean enforceLicense;

    public LicenseManager(String apiKey, String licenseServerUrl, Path dataDir, PublicKey offlinePublicKey) {
        this.apiKey = apiKey;
        this.deviceId = DeviceFingerprint.compute();
        this.offlineValidator = new OfflineKeyValidator(offlinePublicKey);
        this.onlineValidator = new OnlineKeyValidator(licenseServerUrl);
        this.cache = new LicenseCache(dataDir, deviceId);

        this.enforceLicense = isEnforceLicense();

        this.bypassMode = !isProductionBuild()
                && (apiKey == null || apiKey.isBlank())
                && (licenseServerUrl == null || licenseServerUrl.isBlank()
                        || "none".equalsIgnoreCase(licenseServerUrl));
    }

    /**
     * Validate the license and return the result.
     * Call once at startup.
     */
    public LicenseResult validate() {
        if (!enforceLicense) {
            logger.info("License enforcement disabled — granting PRO tier");
            LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "dev@local.cloud", deviceId, Long.MAX_VALUE);
            cache.write(result);
            return result;
        }

        // Clock tamper detection
        long now = java.time.Instant.now().getEpochSecond();
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
            logger.info("License bypass mode — no API key required (development mode)");
            LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "dev@local.cloud", deviceId, Long.MAX_VALUE);
            cache.write(result);
            return result;
        }

        if (apiKey == null || apiKey.isBlank()) {
            return checkCacheGrace("No API key provided. Set LOCALCLOUD_API_KEY or get a key at https://local.cloud");
        }

        LicenseResult result;

        if (apiKey.startsWith("lck_")) {
            result = offlineValidator.validate(apiKey, deviceId);
        } else if (apiKey.startsWith("lco_")) {
            result = onlineValidator.validate(apiKey, deviceId);
            if (!result.isValid() && result.errorMessage() != null
                    && result.errorMessage().contains("unreachable")) {
                return checkCacheGrace(result.errorMessage());
            }
        } else {
            result = LicenseResult.invalid(
                    "Unknown key format. Keys start with 'lco_' (online) or 'lck_' (offline)");
        }

        if (result.isValid()) {
            cache.write(result);
            logger.info("License valid — tier={}, email={}", result.tier(), result.email());
        } else {
            logger.warn("License validation failed: {}", result.errorMessage());
        }

        return result;
    }

    public String getDeviceId() {
        return deviceId;
    }

    private LicenseResult checkCacheGrace(String originalError) {
        LicenseResult cached = cache.read();
        if (cached != null && cached.isValid()
                && cached.expiresEpoch() > java.time.Instant.now().getEpochSecond()
                && cache.isWithinGracePeriod(GRACE_HOURS)) {
            logger.info("Using cached license (grace period) — tier={}", cached.tier());
            cache.write(cached);
            return cached;
        }
        return LicenseResult.invalid(originalError);
    }
}
