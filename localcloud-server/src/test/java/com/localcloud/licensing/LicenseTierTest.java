package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LicenseTierTest {

    // -----------------------------------------------------------------------
    // fromString()
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // includes() — ordinal-based tier comparison
    // -----------------------------------------------------------------------

    @Test
    void includes_nullRequirement_alwaysTrue() {
        assertTrue(LicenseTier.COMMUNITY.includes(null));
        assertTrue(LicenseTier.PRO.includes(null));
    }

    @Test
    void includes_communityIncludesCommunity() {
        assertTrue(LicenseTier.COMMUNITY.includes(LicenseTier.COMMUNITY));
    }

    @Test
    void includes_communityDoesNotIncludeTrial() {
        assertFalse(LicenseTier.COMMUNITY.includes(LicenseTier.TRIAL));
    }

    @Test
    void includes_communityDoesNotIncludePro() {
        assertFalse(LicenseTier.COMMUNITY.includes(LicenseTier.PRO));
    }

    @Test
    void includes_trialIncludesCommunityAndTrial() {
        assertTrue(LicenseTier.TRIAL.includes(LicenseTier.COMMUNITY));
        assertTrue(LicenseTier.TRIAL.includes(LicenseTier.TRIAL));
    }

    @Test
    void includes_trialDoesNotIncludePro() {
        assertFalse(LicenseTier.TRIAL.includes(LicenseTier.PRO));
    }

    @Test
    void includes_proIncludesCommunityTrialAndPro() {
        assertTrue(LicenseTier.PRO.includes(LicenseTier.COMMUNITY));
        assertTrue(LicenseTier.PRO.includes(LicenseTier.TRIAL));
        assertTrue(LicenseTier.PRO.includes(LicenseTier.PRO));
    }

    @Test
    void includes_enterpriseIncludesAll() {
        assertTrue(LicenseTier.ENTERPRISE.includes(LicenseTier.COMMUNITY));
        assertTrue(LicenseTier.ENTERPRISE.includes(LicenseTier.TRIAL));
        assertTrue(LicenseTier.ENTERPRISE.includes(LicenseTier.PRO));
        assertTrue(LicenseTier.ENTERPRISE.includes(LicenseTier.TEAM));
        assertTrue(LicenseTier.ENTERPRISE.includes(LicenseTier.ENTERPRISE));
    }
}
