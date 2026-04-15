package com.localcloud.emulators.workflows.stdlib;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpFunctions {
    private static final Logger logger = LoggerFactory.getLogger(HttpFunctions.class);
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void register(StdlibRegistry registry) {
        registry.register("http.get", args -> httpCall("GET", args));
        registry.register("http.post", args -> httpCall("POST", args));
        registry.register("http.put", args -> httpCall("PUT", args));
        registry.register("http.patch", args -> httpCall("PATCH", args));
        registry.register("http.delete", args -> httpCall("DELETE", args));
    }

    @SuppressWarnings("unchecked")
    private static Object httpCall(String method, List<Object> args) {
        if (args.isEmpty()) throw new RuntimeException("http." + method.toLowerCase() + " requires at least a url argument");

        Map<String, Object> config;
        if (args.get(0) instanceof Map) {
            config = (Map<String, Object>) args.get(0);
        } else if (args.get(0) instanceof String) {
            config = Map.of("url", args.get(0));
        } else {
            throw new RuntimeException("http." + method.toLowerCase() + " first argument must be a URL string or config map");
        }

        String url = (String) config.get("url");
        if (url == null) throw new RuntimeException("http." + method.toLowerCase() + " requires 'url'");

        Map<String, String> headers = config.containsKey("headers") ? (Map<String, String>) config.get("headers") : Collections.emptyMap();
        Object body = config.get("body");
        int timeout = config.containsKey("timeout") ? ((Number) config.get("timeout")).intValue() : 300;

        // Build query string
        if (config.containsKey("query")) {
            Map<String, String> query = (Map<String, String>) config.get("query");
            StringBuilder qs = new StringBuilder();
            for (var entry : query.entrySet()) {
                if (!qs.isEmpty()) qs.append("&");
                qs.append(entry.getKey()).append("=").append(entry.getValue());
            }
            url += (url.contains("?") ? "&" : "?") + qs;
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout));

            for (var entry : headers.entrySet()) {
                reqBuilder.header(entry.getKey(), entry.getValue());
            }

            if (body != null && !method.equals("GET") && !method.equals("DELETE")) {
                String bodyStr = body instanceof String ? (String) body : mapper.writeValueAsString(body);
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(bodyStr));
                if (!headers.containsKey("Content-Type") && !headers.containsKey("content-type")) {
                    reqBuilder.header("Content-Type", "application/json");
                }
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            Map<String, Object> result = new LinkedHashMap<>();
            Object responseBody = response.body();
            try { responseBody = mapper.readValue(response.body(), Object.class); } catch (Exception ignored) {}
            result.put("body", responseBody);
            result.put("code", response.statusCode());

            Map<String, String> respHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> respHeaders.put(k, v.isEmpty() ? "" : v.get(0)));
            result.put("headers", respHeaders);

            return result;
        } catch (Exception e) {
            throw new RuntimeException("HTTP " + method + " failed: " + e.getMessage(), e);
        }
    }
}
