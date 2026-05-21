package com.localcloud.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Stub IAM service for Spanner that returns default permissive responses.
 * Intercepts SetIamPolicy, GetIamPolicy, and TestIamPermissions calls
 * at the gateway level so they never reach the C++ emulator (which doesn't
 * support IAM).
 *
 * Uses regex-based routing because Armeria's annotated service path patterns
 * do not support the ':' character used in Google Cloud REST API custom methods.
 */
public class SpannerIamService implements HttpService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Pattern IAM_PATTERN = Pattern.compile(
            "^/v1/projects/(?<project>[^/]+)/instances/(?<instance>[^/]+)(?:/databases/(?<database>[^/]+))?:(?<method>setIamPolicy|getIamPolicy|testIamPermissions)$");

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        String path = ctx.path();
        Matcher matcher = IAM_PATTERN.matcher(path);

        if (!matcher.matches()) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }

        String project = matcher.group("project");
        String instance = matcher.group("instance");
        String database = matcher.group("database");
        String method = matcher.group("method");

        // Aggregate the request to read the body, wrap in HttpResponse
        return HttpResponse.from(req.aggregate().thenApply(aggReq -> {
            try {
                return switch (method) {
                    case "setIamPolicy" -> echoPolicy(aggReq);
                    case "getIamPolicy" -> defaultPolicy(project);
                    case "testIamPermissions" -> allPermissionsGranted(aggReq);
                    default -> HttpResponse.of(HttpStatus.NOT_FOUND);
                };
            } catch (Exception e) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                        "IAM stub error: " + e.getMessage());
            }
        }).exceptionally(throwable ->
                HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                        "IAM stub error: " + throwable.getMessage())));
    }

    private HttpResponse echoPolicy(AggregatedHttpRequest req) throws Exception {
        String body = req.contentUtf8();
        if (body == null || body.isBlank()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                    mapper.writeValueAsString(Map.of("error", "Empty policy body")));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = mapper.readValue(body, Map.class);
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(policy));
    }

    private HttpResponse defaultPolicy(String project) throws Exception {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", 1);
        policy.put("etag", java.util.Base64.getEncoder().encodeToString("stub".getBytes()));
        policy.put("bindings", List.of(
            Map.of(
                "role", "roles/spanner.admin",
                "members", List.of("user:emulator@localcloud.dev")
            )
        ));
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(policy));
    }

    private HttpResponse allPermissionsGranted(AggregatedHttpRequest req) throws Exception {
        String body = req.contentUtf8();
        @SuppressWarnings("unchecked")
        Map<String, Object> request = mapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        List<String> requestedPermissions = (List<String>) request.getOrDefault("permissions", List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("permissions", requestedPermissions);
        return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
