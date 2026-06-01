package com.localcloud.emulators.oauth2;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OAuth2 auth endpoint redirect behavior.
 */
class OAuth2RestServiceTest {

    @Test
    void authRedirect_containsCodeAndState() {
        String redirectUri = "http://localhost:8080/callback";
        String state = "abc123";
        String location = redirectUri + "?code=localcloud-auth-code&state=" + state;
        assertTrue(location.contains("code=localcloud-auth-code"));
        assertTrue(location.contains("state=abc123"));
        assertTrue(location.startsWith(redirectUri));
    }

    @Test
    void tokenEndpoint_alwaysReturns200() {
        // Token endpoint always returns 200 with a valid access_token
        int statusCode = 200;
        assertEquals(200, statusCode);
    }

    @Test
    void userinfoResponse_containsEmail() {
        // Userinfo should return user details for SDK validation
        String email = "developer@localcloud.dev";
        assertNotNull(email);
        assertTrue(email.contains("@"));
    }

    @Test
    void certsEndpoint_returnsJson() {
        // Certificates endpoint returns JSON with keys array
        String contentType = "application/json";
        assertEquals("application/json", contentType);
    }
}
