package com.localcloud.license.validation;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.keys.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.UUID;

public class LicenseValidator {

    private static final Logger logger = LoggerFactory.getLogger(LicenseValidator.class);
    private final ApiKeyRepository keyRepo;
    private final AuthRepository authRepo;
    private final DeviceTracker deviceTracker;

    public LicenseValidator(ApiKeyRepository keyRepo, AuthRepository authRepo, DeviceTracker deviceTracker) {
        this.keyRepo = keyRepo;
        this.authRepo = authRepo;
        this.deviceTracker = deviceTracker;
    }

    public ValidationResult validate(String keyHash, String deviceFingerprint) throws SQLException {
        if (keyHash == null || keyHash.isBlank()) {
            return ValidationResult.invalid("Missing API key");
        }
        if (!keyRepo.keyExists(keyHash)) {
            return ValidationResult.invalid("Invalid or revoked API key");
        }
        UUID userId = keyRepo.getUserIdForKey(keyHash);
        if (userId == null) {
            return ValidationResult.invalid("Key not associated with user");
        }
        String tier = keyRepo.getTierForKey(keyHash);
        if (deviceFingerprint != null && !deviceFingerprint.isBlank()) {
            deviceTracker.recordDevice(userId, deviceFingerprint);
        }
        return ValidationResult.valid(userId, tier);
    }

    public record ValidationResult(boolean valid, UUID userId, String tier, String reason) {
        public static ValidationResult valid(UUID userId, String tier) {
            return new ValidationResult(true, userId, tier, null);
        }
        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, null, null, reason);
        }
    }
}
