package com.localcloud.emulators.vertexai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexAiRestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generateContentReturnsGeminiShapedResponse() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("vertex_generate");
        try {
            VertexAiRestService service = new VertexAiEmulator(testDataSource.getDataSource(), 8080).getRestService();
            String request = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello local model\"}]}]}";
            var json = mapper.readTree(body(service.generateContent(
                    "local-project", "us-central1", "google", "gemini-local", request)));

            assertTrue(json.has("candidates"));
            assertEquals("model", json.get("candidates").get(0).get("content").get("role").asText());
            assertTrue(json.get("usageMetadata").get("totalTokenCount").asInt() > 0);
        } finally {
            testDataSource.close();
        }
    }

    private String body(com.linecorp.armeria.common.HttpResponse response) {
        return response.aggregate().join().contentUtf8();
    }
}
