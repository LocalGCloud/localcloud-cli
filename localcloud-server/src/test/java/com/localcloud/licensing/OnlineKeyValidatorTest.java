package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnlineKeyValidatorTest {

    @Test
    void bypassModeAcceptsAnyOnlineKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_testkey123", "device-id");
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void rejectsNonOnlinePrefix() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lck_someofflinekey", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("prefix"));
    }

    @Test
    void rejectsNullKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate(null, "device-id");
        assertFalse(result.isValid());
    }

    @Test
    void rejectsEmptyKey() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_", "device-id");
        assertFalse(result.isValid());
    }

    @Test
    void unreachableServerReturnsError() {
        OnlineKeyValidator validator = new OnlineKeyValidator("http://localhost:19999");
        LicenseResult result = validator.validate("lco_testkey123", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("unreachable") || result.errorMessage().contains("connect"));
    }
}
