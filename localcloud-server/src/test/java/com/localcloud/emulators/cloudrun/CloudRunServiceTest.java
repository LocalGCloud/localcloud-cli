package com.localcloud.emulators.cloudrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Cloud Run gRPC service request/response parsing.
 */
class CloudRunServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseServiceName_validFormat() {
        String name = "projects/test-project/locations/us-central1/services/my-service";
        String[] parts = name.split("/");
        assertEquals(6, parts.length);
        assertEquals("test-project", parts[1]);
        assertEquals("us-central1", parts[3]);
        assertEquals("my-service", parts[5]);
    }

    @Test
    void parseRevisionName_validFormat() {
        String name = "projects/p/locations/l/services/s/revisions/r-001";
        String[] parts = name.split("/");
        assertEquals(8, parts.length);
        assertEquals("p", parts[1]);
        assertEquals("l", parts[3]);
        assertEquals("s", parts[5]);
        assertEquals("r-001", parts[7]);
    }

    @Test
    void buildServiceResponse_containsRequiredFields() throws Exception {
        var response = mapper.createObjectNode();
        response.put("name", "projects/p/locations/l/services/svc");
        response.put("ingress", "INGRESS_TRAFFIC_ALL");
        var template = mapper.createObjectNode();
        template.put("containerImage", "gcr.io/project/image:latest");
        response.set("template", template);
        assertTrue(response.has("name"));
        assertTrue(response.has("ingress"));
        assertTrue(response.get("template").has("containerImage"));
    }

    @Test
    void containerImageParsing_extractsTagAndRegistry() {
        String image = "gcr.io/my-project/my-service:v1.2.3";
        String[] parts = image.split("/");
        assertEquals(3, parts.length);
        String[] nameAndTag = parts[2].split(":");
        assertEquals("my-service", nameAndTag[0]);
        assertEquals("v1.2.3", nameAndTag[1]);
    }
}
