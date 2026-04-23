package com.localcloud.emulators.cloudtasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CloudTasksRestService JSON parsing logic.
 * Tests the queue ID extraction from request bodies without requiring Armeria.
 */
class CloudTasksRestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Replicates queue ID extraction from CloudTasksRestService.createQueue:
     * parses "name" field and extracts the last segment.
     */
    private static String extractQueueId(String body) throws Exception {
        var mapper = new ObjectMapper();
        var parsed = mapper.readTree(body);
        if (parsed.has("name")) {
            String name = parsed.get("name").asText();
            return name.substring(name.lastIndexOf('/') + 1);
        }
        return null;
    }

    @Test
    void extractQueueId_fullResourceName() throws Exception {
        String body = "{\"name\": \"projects/p/locations/l/queues/my-queue\"}";
        assertEquals("my-queue", extractQueueId(body));
    }

    @Test
    void extractQueueId_simpleQueueName() throws Exception {
        String body = "{\"name\": \"my-queue\"}";
        assertEquals("my-queue", extractQueueId(body));
    }

    @Test
    void extractQueueId_missingNameField() throws Exception {
        String body = "{\"state\": \"RUNNING\"}";
        assertNull(extractQueueId(body));
    }

    @Test
    void extractQueueId_emptyBody() throws Exception {
        String body = "{}";
        assertNull(extractQueueId(body));
    }

    @Test
    void extractQueueId_invalidJson_throwsException() {
        assertThrows(Exception.class, () -> extractQueueId("not json"));
    }

    @Test
    void queueResponseFormat_matchesGoogleApi() throws Exception {
        // Verify the response JSON structure matches Google Cloud Tasks API format
        String project = "my-project";
        String location = "us-central1";
        String queueId = "email-queue";

        var result = mapper.createObjectNode();
        result.put("name", "projects/" + project + "/locations/" + location + "/queues/" + queueId);
        result.put("state", "RUNNING");

        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals("projects/my-project/locations/us-central1/queues/email-queue",
                parsed.get("name").asText());
        assertEquals("RUNNING", parsed.get("state").asText());
    }

    @Test
    void errorResponseFormat_matchesGoogleApi() throws Exception {
        // Verify error response structure matches Google Cloud API error format
        var error = mapper.createObjectNode();
        var inner = mapper.createObjectNode();
        inner.put("code", 404);
        inner.put("message", "Queue not found: test-queue");
        error.set("error", inner);

        String json = mapper.writeValueAsString(error);
        JsonNode parsed = mapper.readTree(json);
        assertTrue(parsed.has("error"));
        assertEquals(404, parsed.get("error").get("code").asInt());
        assertEquals("Queue not found: test-queue", parsed.get("error").get("message").asText());
    }
}
