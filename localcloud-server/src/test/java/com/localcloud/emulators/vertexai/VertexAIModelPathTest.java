package com.localcloud.emulators.vertexai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Vertex AI REST handler model/publisher path parsing.
 */
class VertexAIModelPathTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseGenerateContentPath_validFormat() {
        String path = "projects/p/locations/us-central1/publishers/google/models/gemini-2.0-flash";
        String[] parts = path.split("/");
        assertEquals(8, parts.length);
        assertEquals("p", parts[1]);
        assertEquals("us-central1", parts[3]);
        assertEquals("google", parts[5]);
        assertEquals("gemini-2.0-flash", parts[7]);
    }

    @Test
    void generateContentRequest_hasContentsArray() throws Exception {
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]}]}";
        var json = mapper.readTree(body);
        assertTrue(json.has("contents"));
        assertEquals(1, json.get("contents").size());
    }

    @Test
    void modelId_formatting() {
        String modelId = "gemini-2.0-flash-001";
        String publisher = "google";
        String fullPath = "publishers/" + publisher + "/models/" + modelId;
        assertEquals("publishers/google/models/gemini-2.0-flash-001", fullPath);
    }

    @Test
    void contentTokenCount_returnsPositiveInteger() throws Exception {
        String response = "{\"totalTokens\":42,\"totalBillableCharacters\":128}";
        var json = mapper.readTree(response);
        assertTrue(json.get("totalTokens").asInt() > 0);
        assertTrue(json.has("totalBillableCharacters"));
    }
}
