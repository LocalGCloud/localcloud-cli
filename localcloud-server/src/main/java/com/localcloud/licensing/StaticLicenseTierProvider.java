package com.localcloud.licensing;

/** License tier determined once at startup from LicenseResult. */
public final class StaticLicenseTierProvider implements LicenseTierProvider {
    private final LicenseTier tier;

    public StaticLicenseTierProvider(LicenseTier tier) {
        this.tier = tier;
    }

    @Override
    public LicenseTier currentTier() {
        return tier;
    }
}
