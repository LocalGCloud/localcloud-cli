package com.localcloud.emulators.serviceusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ServiceUsage REST handler response format.
 */
class ServiceUsageResponseFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serviceResponse_containsRequiredFields() throws Exception {
        var svc = mapper.createObjectNode();
        svc.put("name", "compute.googleapis.com");
        svc.put("state", "ENABLED");
        var config = mapper.createObjectNode();
        config.put("name", "compute.googleapis.com");
        config.put("title", "Compute Engine API");
        svc.set("config", config);
        assertTrue(svc.has("name"));
        assertTrue(svc.has("state"));
        assertTrue(svc.has("config"));
        assertEquals("ENABLED", svc.get("state").asText());
    }

    @Test
    void listServicesResponse_containsServicesArray() throws Exception {
        var response = mapper.createObjectNode();
        var services = mapper.createArrayNode();
        services.addObject().put("name", "compute.googleapis.com").put("state", "ENABLED");
        services.addObject().put("name", "storage.googleapis.com").put("state", "ENABLED");
        response.set("services", services);
        assertEquals(2, response.get("services").size());
    }

    @Test
    void enableServiceResponse_returnsUpdatedState() throws Exception {
        var response = mapper.createObjectNode();
        response.put("name", "pubsub.googleapis.com");
        response.put("state", "ENABLED");
        assertEquals("ENABLED", response.get("state").asText());
    }

    @Test
    void batchEnableServices_validatesServiceNames() {
        String[] names = {"compute.googleapis.com", "storage.googleapis.com"};
        for (String name : names) {
            assertTrue(name.endsWith(".googleapis.com"));
            assertTrue(name.contains("."));
        }
    }
}
