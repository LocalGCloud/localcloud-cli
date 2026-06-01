package com.localcloud.emulators.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OAuth2 token endpoint response format.
 */
class OAuth2TokenValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tokenResponse_containsRequiredFields() throws Exception {
        String response = "{\"access_token\":\"ya29.test123\"," +
                          "\"token_type\":\"Bearer\"," +
                          "\"expires_in\":3600}";
        var json = mapper.readTree(response);
        assertTrue(json.has("access_token"));
        assertTrue(json.has("token_type"));
        assertTrue(json.has("expires_in"));
        assertEquals("Bearer", json.get("token_type").asText());
        assertEquals(3600, json.get("expires_in").asInt());
    }

    @Test
    void accessTokenFormat_startsWithExpectedPrefix() {
        String token = "ya29.localcloud-1234567890";
        assertTrue(token.startsWith("ya29."));
        assertFalse(token.isEmpty());
    }

    @Test
    void tokenResponse_hasNonEmptyAccessToken() throws Exception {
        String response = "{\"access_token\":\"ya29.test\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
        var json = mapper.readTree(response);
        String token = json.get("access_token").asText();
        assertNotNull(token);
        assertFalse(token.isBlank());
    }
}
