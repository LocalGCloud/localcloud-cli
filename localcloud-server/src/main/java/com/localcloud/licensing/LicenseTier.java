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
