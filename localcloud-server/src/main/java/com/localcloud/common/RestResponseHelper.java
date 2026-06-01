package com.localcloud.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Route;

import java.util.function.BiFunction;

/**
 * Shared REST response utilities used across all emulator REST services.
 * <p>
 * Before this class, 13 services had their own copy of {@code errorResponse()},
 * {@code json()}, and {@code ObjectMapper} instances. This class consolidates them
 * into a single place for consistency, reduced memory footprint, and simpler refactoring.
 * <p>
 * All methods are static and thread-safe. The shared {@link ObjectMapper} is configured
 * once and reused.
 */
public final class RestResponseHelper {

    /** Shared, thread-safe ObjectMapper instance. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Return a 2xx JSON response.
     */
    public static HttpResponse ok(ObjectNode node) {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, MAPPER.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            return error(500, "Serialization failed: " + e.getMessage());
        }
    }

    /**
     * Return a successful JSON response with a custom status code.
     */
    public static HttpResponse ok(HttpStatus status, ObjectNode node) {
        try {
            return HttpResponse.of(status, MediaType.JSON, MAPPER.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            return error(500, "Serialization failed: " + e.getMessage());
        }
    }

    /**
     * Return a structured JSON error response.
     */
    public static HttpResponse error(int code, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        ObjectNode inner = error.putObject("error");
        inner.put("code", code);
        inner.put("message", message != null ? message : "Unknown error");
        inner.put("status", String.valueOf(code));
        try {
            return HttpResponse.of(HttpStatus.valueOf(code), MediaType.JSON, MAPPER.writeValueAsString(error));
        } catch (JsonProcessingException e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                    message != null ? message : "Unknown error");
        }
    }

    /**
     * Return a structured JSON error response with gRPC-style status code.
     * Used by gRPC-transcoded services that expect gRPC error codes.
     */
    public static HttpResponse grpcError(int code, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message != null ? message : "Unknown error");
        try {
            return HttpResponse.of(HttpStatus.valueOf(code < 100 ? 500 : code), MediaType.JSON,
                    MAPPER.writeValueAsString(error));
        } catch (JsonProcessingException e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                    message != null ? message : "Unknown error");
        }
    }

    /**
     * Parse a JSON request body safely.
     */
    public static com.fasterxml.jackson.databind.JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return MAPPER.createObjectNode();
        }
    }

    /**
     * Serialize an object to JSON string.
     */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * Register a regex-based custom method route.
     * <p>
     * Google Cloud REST APIs use {@code :verb} custom methods (e.g., {@code :encrypt},
     * {@code :destroy}, {@code :generateContent}). Armeria's annotation parser treats
     * {@code :} as a regex delimiter inside path parameters, so these routes must be
     * registered manually via {@link ServerBuilder#service(Route, ...)}.
     * <p>
     * This helper reduces boilerplate for the 36+ regex routes in LocalCloudApplication.
     *
     * @param sb the server builder
     * @param method HTTP method (POST, GET, etc.)
     * @param regexPath the regex path pattern with named groups
     * @param handler handler that receives the aggregated request body and returns a response
     */
    public static void registerCustomVerbRoute(
            ServerBuilder sb,
            com.linecorp.armeria.common.HttpMethod method,
            String regexPath,
            java.util.function.Function<com.linecorp.armeria.common.AggregatedHttpRequest, HttpResponse> handler) {
        sb.service(
                Route.builder()
                        .methods(method)
                        .path(regexPath)
                        .build(),
                (ctx, req) -> {
                    var agg = req.aggregate().join();
                    return handler.apply(agg);
                });
    }

    /**
     * Register a regex-based custom method route that passes the request context.
     * Use this when the handler needs access to path parameters or headers.
     *
     * @param sb the server builder
     * @param method HTTP method
     * @param regexPath the regex path pattern with named groups
     * @param handler handler that receives (ctx, req) and returns a response
     */
    public static void registerCustomVerbRoute(
            ServerBuilder sb,
            com.linecorp.armeria.common.HttpMethod method,
            String regexPath,
            BiFunction<com.linecorp.armeria.server.ServiceRequestContext,
                    com.linecorp.armeria.common.AggregatedHttpRequest, HttpResponse> handler) {
        sb.service(
                Route.builder()
                        .methods(method)
                        .path(regexPath)
                        .build(),
                (ctx, req) -> {
                    var agg = req.aggregate().join();
                    return handler.apply(ctx, agg);
                });
    }

    private RestResponseHelper() {
        // utility class
    }
}
