package com.localcloud.emulators.serviceusage;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service Usage API REST stub.
 * <p>
 * Google client libraries (including Terraform) check whether a service is
 * enabled before making API calls. This stub always returns ENABLED for all
 * services, bypassing the real GCP service enablement check.
 * <p>
 * Registered at /v1 to match the google.api.serviceusage.v1 REST surface.
 * Paths do not overlap with CloudResourceManager or SecretManager since they
 * use /services/... vs /secrets/... vs /projects/{id}.
 */
public class ServiceUsageRestService {

    private static final Logger logger = LoggerFactory.getLogger(ServiceUsageRestService.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Get the enablement state of a single service.
     * GET /v1/projects/{project}/services/{service}
     */
    @Get("/projects/{project}/services/{service}")
    public HttpResponse getService(@Param String project, @Param String service) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("name", service);
            result.put("state", "ENABLED");
            result.put("parent", "projects/" + project);
            ObjectNode config = result.putObject("config");
            config.put("name", service);
            config.put("title", service.replace(".googleapis.com", ""));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            return errorResponse(500, "Internal error");
        }
    }

    /**
     * List enabled services for a project.
     * GET /v1/projects/{project}/services
     */
    @Get("/projects/{project}/services")
    public HttpResponse listServices(@Param String project) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.set("services", mapper.createArrayNode());
            result.put("nextPageToken", "");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            return errorResponse(500, "Internal error");
        }
    }

    /**
     * Enable a single service (custom method via regex route).
     * POST /v1/projects/{project}/services/{service}:enable
     */
    @Post("regex:^/projects/(?<project>[^/]+)/services/(?<service>[^:]+):enable$")
    public HttpResponse enableService(@Param String project, @Param String service) {
        try {
            ObjectNode operation = mapper.createObjectNode();
            operation.put("name", "operations/su-" + System.currentTimeMillis());
            operation.put("done", true);
            ObjectNode resp = operation.putObject("response");
            resp.put("name", service);
            resp.put("state", "ENABLED");
            resp.put("@type", "type.googleapis.com/google.api.serviceusage.v1.EnableServiceResponse");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(operation));
        } catch (Exception e) {
            return errorResponse(500, "Internal error");
        }
    }

    /**
     * Batch enable services (custom method via regex route).
     * POST /v1/projects/{project}/services:batchEnable
     */
    @Post("regex:^/projects/(?<project>[^/]+)/services:batchEnable$")
    public HttpResponse batchEnableServices(@Param String project, String body) {
        try {
            ObjectNode operation = mapper.createObjectNode();
            operation.put("name", "operations/su-batch-" + System.currentTimeMillis());
            operation.put("done", true);
            ObjectNode resp = operation.putObject("response");
            resp.put("@type", "type.googleapis.com/google.api.serviceusage.v1.BatchEnableServicesResponse");
            ArrayNode services = resp.putArray("services");
            if (body != null && !body.isBlank()) {
                JsonNode parsed = mapper.readTree(body);
                if (parsed.has("serviceIds") && parsed.get("serviceIds").isArray()) {
                    for (JsonNode id : parsed.get("serviceIds")) {
                        ObjectNode svc = services.addObject();
                        svc.put("name", id.asText());
                        svc.put("state", "ENABLED");
                    }
                }
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(operation));
        } catch (Exception e) {
            return errorResponse(500, "Internal error");
        }
    }

    private HttpResponse errorResponse(int code, String message) {
        try {
            ObjectNode error = mapper.createObjectNode();
            ObjectNode inner = mapper.createObjectNode();
            inner.put("code", code);
            inner.put("message", message);
            error.set("error", inner);
            return HttpResponse.of(HttpStatus.valueOf(code), MediaType.JSON, mapper.writeValueAsString(error));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
