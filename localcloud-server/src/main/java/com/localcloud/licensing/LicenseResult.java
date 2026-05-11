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
