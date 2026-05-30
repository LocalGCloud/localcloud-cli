package com.localcloud.emulators.vertexai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * REST-only Gemini-compatible Vertex AI surface for local testing.
 */
public class VertexAiRestService {

    private final VertexAiStore store;
    private final VertexAiEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String backend;
    private final IAMPolicyRestHandler iamHandler;

    public VertexAiRestService(VertexAiStore store, VertexAiEmulator emulator) {
        this(store, emulator, null);
    }

    public VertexAiRestService(VertexAiStore store, VertexAiEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.store = store;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
        this.backend = System.getenv().getOrDefault("LOCALCLOUD_VERTEX_BACKEND", "stub");
    }

    @Post("/projects/{project}/locations/{location}/publishers/{publisher}/models/{model}:generateContent")
    public HttpResponse generateContent(@Param String project, @Param String location, @Param String publisher,
                                        @Param String model, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode request = readTree(body);
            String prompt = promptText(request);
            int promptTokens = countTokens(prompt);
            String output = stubResponse(model, prompt);
            int responseTokens = countTokens(output);
            ObjectNode response = generateResponse(model, output, promptTokens, responseTokens);
            record(project, location, publisher, model, "generateContent", body, response, promptTokens, responseTokens);
            return json(HttpStatus.OK, response);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/projects/{project}/locations/{location}/publishers/{publisher}/models/{model}:streamGenerateContent")
    public HttpResponse streamGenerateContent(@Param String project, @Param String location, @Param String publisher,
                                              @Param String model, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode request = readTree(body);
            String prompt = promptText(request);
            int promptTokens = countTokens(prompt);
            String output = stubResponse(model, prompt);
            int responseTokens = countTokens(output);
            ArrayNode chunks = mapper.createArrayNode();
            chunks.add(generateResponse(model, output, promptTokens, responseTokens));
            ObjectNode finalChunk = mapper.createObjectNode();
            finalChunk.putObject("usageMetadata")
                    .put("promptTokenCount", promptTokens)
                    .put("candidatesTokenCount", responseTokens)
                    .put("totalTokenCount", promptTokens + responseTokens);
            chunks.add(finalChunk);
            record(project, location, publisher, model, "streamGenerateContent", body, chunks, promptTokens, responseTokens);
            return json(HttpStatus.OK, chunks);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/projects/{project}/locations/{location}/publishers/{publisher}/models/{model}:embedContent")
    public HttpResponse embedContent(@Param String project, @Param String location, @Param String publisher,
                                     @Param String model, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode request = readTree(body);
            String prompt = promptText(request);
            ObjectNode response = mapper.createObjectNode();
            ObjectNode embedding = response.putObject("embedding");
            ArrayNode values = embedding.putArray("values");
            for (float value : deterministicEmbedding(prompt, 16)) {
                values.add(value);
            }
            response.putObject("usageMetadata").put("totalTokenCount", countTokens(prompt));
            record(project, location, publisher, model, "embedContent", body, response, countTokens(prompt), 0);
            return json(HttpStatus.OK, response);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/projects/{project}/locations/{location}/publishers/{publisher}/models/{model}:countTokens")
    public HttpResponse countTokens(@Param String project, @Param String location, @Param String publisher,
                                    @Param String model, String body) {
        return tokenResponse(project, location, publisher, model, "countTokens", body);
    }

    @Post("/projects/{project}/locations/{location}/publishers/{publisher}/models/{model}:computeTokens")
    public HttpResponse computeTokens(@Param String project, @Param String location, @Param String publisher,
                                      @Param String model, String body) {
        return tokenResponse(project, location, publisher, model, "computeTokens", body);
    }

    private HttpResponse tokenResponse(String project, String location, String publisher, String model,
                                       String method, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode request = readTree(body);
            String prompt = promptText(request);
            int tokens = countTokens(prompt);
            ObjectNode response = mapper.createObjectNode();
            response.put("totalTokens", tokens);
            response.put("totalBillableCharacters", prompt.length());
            ArrayNode infos = response.putArray("tokensInfo");
            ObjectNode info = infos.addObject();
            info.put("role", "user");
            info.put("tokenCount", tokens);
            record(project, location, publisher, model, method, body, response, tokens, 0);
            return json(HttpStatus.OK, response);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private ObjectNode generateResponse(String model, String output, int promptTokens, int responseTokens) {
        ObjectNode response = mapper.createObjectNode();
        response.put("modelVersion", model);
        ArrayNode candidates = response.putArray("candidates");
        ObjectNode candidate = candidates.addObject();
        candidate.put("index", 0);
        candidate.put("finishReason", "STOP");
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        content.putArray("parts").addObject().put("text", output);
        response.putObject("usageMetadata")
                .put("promptTokenCount", promptTokens)
                .put("candidatesTokenCount", responseTokens)
                .put("totalTokenCount", promptTokens + responseTokens);
        return response;
    }

    private String promptText(JsonNode root) {
        List<String> parts = new ArrayList<>();
        collectText(root.path("contents"), parts);
        if (parts.isEmpty()) {
            collectText(root.path("content"), parts);
        }
        return String.join("\n", parts).trim();
    }

    private void collectText(JsonNode node, List<String> out) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collectText(child, out));
            return;
        }
        if (node.has("text")) {
            out.add(node.get("text").asText(""));
        }
        if (node.has("parts")) {
            collectText(node.get("parts"), out);
        }
    }

    private int countTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    private String stubResponse(String model, String prompt) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((model + "\n" + prompt).getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        if (prompt == null || prompt.isBlank()) {
            return "LocalCloud Vertex AI stub response. model=" + model + " trace=" + digest;
        }
        return "LocalCloud Vertex AI stub response for model " + model + ": " + prompt + " [" + digest + "]";
    }

    private float[] deterministicEmbedding(String text, int size) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
        float[] values = new float[size];
        for (int i = 0; i < size; i++) {
            int raw = digest[i % digest.length] & 0xFF;
            values[i] = (raw - 128) / 128.0f;
        }
        return values;
    }

    private JsonNode readTree(String body) throws Exception {
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private void record(String project, String location, String publisher, String model, String method,
                        String requestJson, JsonNode response, int promptTokens, int responseTokens) {
        try {
            store.recordRequest(project, location, publisher, model, method, requestJson == null ? "{}" : requestJson,
                    response.toString(), promptTokens, responseTokens, backend);
        } catch (Exception ignored) {
        }
    }

    private HttpResponse json(HttpStatus status, JsonNode node) {
        return HttpResponse.of(status, MediaType.JSON, node.toString());
    }

    // IAM Policy endpoints are handled by the generic catch-all in LocalCloudApplication.

    private HttpResponse error(HttpStatus status, String message) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode inner = out.putObject("error");
        inner.put("code", status.code());
        inner.put("message", message != null ? message : "Vertex AI request failed");
        inner.put("status", status.reasonPhrase().replace(' ', '_').toUpperCase());
        return HttpResponse.of(status, MediaType.JSON, out.toString());
    }
}
