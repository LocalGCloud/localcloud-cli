package com.localcloud.licensing;

/** Provides the current license tier at runtime. */
public interface LicenseTierProvider {
    LicenseTier currentTier();
}
