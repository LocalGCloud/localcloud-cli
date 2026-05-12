package com.localcloud.license.validation;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.keys.ApiKeyRepository;
import com.localcloud.license.trial.TrialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class LicenseValidator {

    private static final Logger logger = LoggerFactory.getLogger(LicenseValidator.class);
    private static final int JWT_VALID_HOURS = 4;

    private final ApiKeyRepository keyRepo;
    private final AuthRepository authRepo;
    private final DeviceTracker deviceTracker;
    private final TrialRepository trialRepo;

    public LicenseValidator(ApiKeyRepository keyRepo, AuthRepository authRepo,
                            DeviceTracker deviceTracker, TrialRepository trialRepo) {
        this.keyRepo = keyRepo;
        this.authRepo = authRepo;
        this.deviceTracker = deviceTracker;
        this.trialRepo = trialRepo;
    }

    public ValidationResult validate(String rawKey, String deviceId) {
        if (rawKey == null || !rawKey.startsWith("lco_")) {
            return ValidationResult.invalid("Invalid key format — expected lco_ prefix");
        }
        try {
            ApiKeyRepository.KeyInfo keyInfo = keyRepo.findActiveKeyByHash(rawKey);
            if (keyInfo == null) return ValidationResult.invalid("Unknown or revoked key");

            // Enforce trial expiry
            if ("trial".equals(keyInfo.tier())) {
                TrialRepository.TrialInfo trial = trialRepo.getTrialInfo(keyInfo.userId());
                if (trial == null || trial.expiresAt() < Instant.now().getEpochSecond()) {
                    return ValidationResult.invalid(
                        "Trial expired. Upgrade at https://localcloud.dev/pricing");
                }
            }

            // Enforce subscription/key-level expiry
            if (keyInfo.expiresAt() != null && keyInfo.expiresAt() < Instant.now().getEpochSecond()) {
                return ValidationResult.invalid(
                    "License expired. Renew at https://localcloud.dev/pricing");
            }

            // Track device
            if (deviceId != null && !deviceId.isBlank() && keyInfo.userId() != null) {
                deviceTracker.recordDevice(keyInfo.userId(), deviceId);
            }

            long expires = Instant.now().plus(JWT_VALID_HOURS, ChronoUnit.HOURS).getEpochSecond();
            String email = keyInfo.userEmail() != null ? keyInfo.userEmail() : "unknown";
            return new ValidationResult(true, keyInfo.tier(), email, expires, null);

        } catch (Exception e) {
            logger.error("License validation error: {}", e.getMessage());
            return ValidationResult.invalid("Validation failed: " + e.getMessage());
        }
    }

    public record ValidationResult(boolean valid, String tier, String email,
                                    long expiresEpoch, String errorMessage) {
        public static ValidationResult invalid(String msg) {
            return new ValidationResult(false, null, null, 0, msg);
        }
    }
}
