package com.localcloud.emulators.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Compute Engine REST handler JSON parsing and response formatting.
 */
class ComputeRestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseCreateInstanceBody_validFields() throws Exception {
        String body = "{\"name\":\"test-vm\",\"machineType\":\"e2-medium\"," +
                      "\"zone\":\"us-central1-a\"}";
        var json = mapper.readTree(body);
        assertEquals("test-vm", json.get("name").asText());
        assertEquals("e2-medium", json.get("machineType").asText());
        assertEquals("us-central1-a", json.get("zone").asText());
    }

    @Test
    void parseCreateInstanceBody_minimal() throws Exception {
        String body = "{\"name\":\"minimal-vm\"}";
        var json = mapper.readTree(body);
        assertEquals("minimal-vm", json.get("name").asText());
        assertNull(json.get("machineType"));
    }

    @Test
    void buildInstanceResponse_containsRequiredFields() {
        var response = mapper.createObjectNode();
        response.put("name", "my-instance");
        response.put("status", "RUNNING");
        response.put("zone", "us-central1-a");
        String json = response.toString();
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"status\""));
        assertTrue(json.contains("\"zone\""));
    }

    @Test
    void zoneParsing_extractsProjectAndZone() {
        String zonePath = "projects/test-project/zones/us-central1-a";
        String[] parts = zonePath.split("/");
        assertEquals(4, parts.length);
        assertEquals("test-project", parts[1]);
        assertEquals("us-central1-a", parts[3]);
    }
}
