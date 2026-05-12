package com.localcloud.licensing;

/**
 * License tiers that control which emulator services are available.
 * Tiers are ordered: COMMUNITY < TRIAL < PRO < TEAM < ENTERPRISE.
 * Use {@link #includes(LicenseTier)} for access control decisions.
 * Service requirements are declared via {@code minTier} in services.yaml.
 */
public enum LicenseTier {
    COMMUNITY,
    TRIAL,
    PRO,
    TEAM,
    ENTERPRISE;

    /**
     * Returns true if this tier grants access to resources requiring the given tier.
     * Ordinal order: COMMUNITY(0) < TRIAL(1) < PRO(2) < TEAM(3) < ENTERPRISE(4).
     * A null requirement always returns true (no restriction).
     */
    public boolean includes(LicenseTier required) {
        if (required == null) return true;
        return this.ordinal() >= required.ordinal();
    }

    /**
     * Parse tier from string, case-insensitive. Defaults to COMMUNITY for null/blank/unknown values.
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
