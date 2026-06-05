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
        registry.register("http.request", args -> {
            if (args.isEmpty() || !(args.get(0) instanceof Map<?, ?> config)) {
                throw new RuntimeException("http.request requires a config map");
            }
            Object method = config.get("method");
            return httpCall(method != null ? String.valueOf(method).toUpperCase(Locale.ROOT) : "GET", args);
        });

        // http.default_retry: predicate for default retry on 5xx/429
        registry.register("http.default_retry", args -> {
            if (args.isEmpty()) return false;
            Object error = args.get(0);
            if (error instanceof Map<?, ?> map) {
                Object code = map.get("code");
                if (code instanceof Number n) {
                    int value = n.intValue();
                    return value == 429 || value >= 500;
                }
                if (code != null) {
                    String text = String.valueOf(code);
                    return "429".equals(text) || text.startsWith("5");
                }
            }
            return false;
        });

        // http.default_retry_non_idempotent: predicate for non-idempotent retry (429, 502-504 only)
        registry.register("http.default_retry_non_idempotent", args -> {
            if (args.isEmpty()) return false;
            Object error = args.get(0);
            if (error instanceof Map<?, ?> map) {
                Object code = map.get("code");
                if (code instanceof Number n) {
                    int value = n.intValue();
                    return value == 429 || value == 502 || value == 503 || value == 504;
                }
                if (code != null) {
                    String text = String.valueOf(code);
                    return "429".equals(text) || "502".equals(text) || "503".equals(text) || "504".equals(text);
                }
            }
            return false;
        });

        // http.append_header: merge headers maps
        registry.register("http.append_header", args -> {
            if (args.size() < 2) throw new RuntimeException("http.append_header requires (headers_map, new_headers)");
            @SuppressWarnings("unchecked")
            Map<String, Object> base = new LinkedHashMap<>((Map<String, Object>) args.get(0));
            @SuppressWarnings("unchecked")
            Map<String, Object> append = (Map<String, Object>) args.get(1);
            append.forEach((k, v) -> base.put(k, v)); // new values override
            return base;
        });
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
