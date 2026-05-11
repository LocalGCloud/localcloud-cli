package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LicenseTierTest {

    @Test
    void communityAllowsOnlyThreeServices() {
        assertTrue(LicenseTier.COMMUNITY.isServiceAllowed("gcs"));
        assertTrue(LicenseTier.COMMUNITY.isServiceAllowed("pubsub"));
        assertTrue(LicenseTier.COMMUNITY.isServiceAllowed("firestore"));
        assertFalse(LicenseTier.COMMUNITY.isServiceAllowed("spanner"));
        assertFalse(LicenseTier.COMMUNITY.isServiceAllowed("bigquery"));
        assertFalse(LicenseTier.COMMUNITY.isServiceAllowed("memorystore"));
    }

    @Test
    void trialAllowsAllServices() {
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("spanner"));
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("bigquery"));
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("gcs"));
        assertTrue(LicenseTier.TRIAL.isServiceAllowed("workflows"));
    }

    @Test
    void proAllowsAllServices() {
        assertTrue(LicenseTier.PRO.isServiceAllowed("spanner"));
        assertTrue(LicenseTier.PRO.isServiceAllowed("bigtable"));
        assertTrue(LicenseTier.PRO.isServiceAllowed("secretmanager"));
    }

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
}
