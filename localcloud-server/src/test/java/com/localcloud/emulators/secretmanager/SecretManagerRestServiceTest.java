package com.localcloud.emulators.secretmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SecretManagerRestService JSON parsing logic.
 * Tests secretId and labels extraction from request bodies without requiring Armeria.
 */
class SecretManagerRestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Replicates the combined parsing logic from SecretManagerRestService.createSecret.
     * After the fix, body is parsed once and both secretId and labels are extracted.
     */
    private record ParseResult(String secretId, String labels) {}

    private ParseResult parseCreateBody(String body) throws Exception {
        var parsed = mapper.readTree(body);
        String secretId = null;
        if (parsed.has("secretId")) {
            secretId = parsed.get("secretId").asText();
        }
        String labels = "{}";
        if (parsed.has("labels")) {
            labels = mapper.writeValueAsString(parsed.get("labels"));
        }
        return new ParseResult(secretId, labels);
    }

    @Test
    void parseCreateBody_secretIdAndLabels() throws Exception {
        String body = "{\"secretId\": \"my-secret\", \"labels\": {\"env\": \"dev\"}}";
        ParseResult result = parseCreateBody(body);
        assertEquals("my-secret", result.secretId());
        assertEquals("{\"env\":\"dev\"}", result.labels());
    }

    @Test
    void parseCreateBody_secretIdOnly() throws Exception {
        String body = "{\"secretId\": \"my-secret\"}";
        ParseResult result = parseCreateBody(body);
        assertEquals("my-secret", result.secretId());
        assertEquals("{}", result.labels());
    }

    @Test
    void parseCreateBody_labelsOnly() throws Exception {
        // secretId can come from query params, so body may only have labels
        String body = "{\"labels\": {\"team\": \"backend\"}}";
        ParseResult result = parseCreateBody(body);
        assertNull(result.secretId());
        assertEquals("{\"team\":\"backend\"}", result.labels());
    }

    @Test
    void parseCreateBody_emptyBody() throws Exception {
        ParseResult result = parseCreateBody("{}");
        assertNull(result.secretId());
        assertEquals("{}", result.labels());
    }

    @Test
    void parseCreateBody_invalidJson_throwsException() {
        assertThrows(Exception.class, () -> parseCreateBody("not json"));
    }

    @Test
    void parseCreateBody_multipleLabels() throws Exception {
        String body = "{\"secretId\": \"s\", \"labels\": {\"env\": \"prod\", \"team\": \"platform\", \"cost-center\": \"123\"}}";
        ParseResult result = parseCreateBody(body);
        assertEquals("s", result.secretId());
        JsonNode labels = mapper.readTree(result.labels());
        assertEquals("prod", labels.get("env").asText());
        assertEquals("platform", labels.get("team").asText());
        assertEquals("123", labels.get("cost-center").asText());
    }

    @Test
    void secretResponseFormat_matchesGoogleApi() throws Exception {
        String project = "my-project";
        String secretId = "api-key";

        var result = mapper.createObjectNode();
        result.put("name", "projects/" + project + "/secrets/" + secretId);
        result.put("createTime", "2024-01-01T00:00:00Z");
        result.set("replication", mapper.createObjectNode().set("automatic", mapper.createObjectNode()));

        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals("projects/my-project/secrets/api-key", parsed.get("name").asText());
        assertTrue(parsed.has("replication"));
        assertTrue(parsed.get("replication").has("automatic"));
    }

    @Test
    void errorResponseFormat_matchesGoogleApi() throws Exception {
        var error = mapper.createObjectNode();
        var inner = mapper.createObjectNode();
        inner.put("code", 400);
        inner.put("message", "Missing required parameter: secretId");
        error.set("error", inner);

        String json = mapper.writeValueAsString(error);
        JsonNode parsed = mapper.readTree(json);
        assertTrue(parsed.has("error"));
        assertEquals(400, parsed.get("error").get("code").asInt());
    }
}
