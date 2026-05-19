package com.localcloud.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpUtil {

    private static final Logger logger = LoggerFactory.getLogger(HttpUtil.class);

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private HttpUtil() {}

    private static String send(HttpRequest request, String methodLabel, String url) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String body = response.body();
                logger.warn("HTTP {} {} failed ({}): {}", methodLabel, url, response.statusCode(), body);
                throw new RuntimeException(String.format("HTTP %s %s failed with status %d: %s",
                        methodLabel, url, response.statusCode(), body));
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP " + methodLabel + " " + url + " failed: " + e.getMessage(), e);
        }
    }

    public static String proxyGet(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return send(request, "GET", url);
    }

    public static String proxyPost(String url, String body) {
        return httpPostAndReturn(url, body, "application/json");
    }

    public static String proxyPut(String url, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request, "PUT", url);
    }

    public static String proxyDelete(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        return send(request, "DELETE", url);
    }

    public static String httpPostAndReturn(String url, String body, String contentType) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", contentType)
                .build();
        return send(request, "POST", url);
    }

    public static Map<String, Object> errorResponse(String message) {
        return Map.of("error", message);
    }

    public static Map<String, Object> errorResponse(String message, Object... extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        if (extra != null) {
            for (int i = 0; i < extra.length - 1; i += 2) {
                if (extra[i] instanceof String key) {
                    result.put(key, extra[i + 1]);
                }
            }
        }
        return result;
    }
}
