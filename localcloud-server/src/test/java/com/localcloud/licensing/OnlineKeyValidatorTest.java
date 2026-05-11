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

    @Test
    void httpUrlToNonLocalhostLogsWarningAndFails() {
        OnlineKeyValidator validator = new OnlineKeyValidator("http://api.example.com");
        LicenseResult result = validator.validate("lco_somekey", "device-id");
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("HTTPS") || result.errorMessage().contains("insecure"),
                "Should reject non-HTTPS non-localhost URL, got: " + result.errorMessage());
    }

    @Test
    void httpLocalhostIsAllowedForDevelopment() {
        OnlineKeyValidator validator = new OnlineKeyValidator("http://localhost:19998");
        LicenseResult result = validator.validate("lco_somekey", "device-id");
        assertFalse(result.isValid());
        assertFalse(result.errorMessage().contains("HTTPS"),
                "localhost http:// should not trigger HTTPS error, got: " + result.errorMessage());
    }
}
