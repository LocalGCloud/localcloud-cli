package com.localcloud.emulators.serviceusage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.common.RestResponseHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service Usage API REST stub.
 * <p>
 * Google client libraries (including Terraform) check whether a service is
 * enabled before making API calls. This stub always returns ENABLED for all
 * services, bypassing the real GCP service enablement check.
 */
public class ServiceUsageRestService {

    private static final Logger logger = LoggerFactory.getLogger(ServiceUsageRestService.class);

    @Get("/projects/{project}/services/{service}")
    public HttpResponse getService(@Param String project, @Param String service) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", service);
            result.put("state", "ENABLED");
            result.put("parent", "projects/" + project);
            ObjectNode config = result.putObject("config");
            config.put("name", service);
            config.put("title", service.replace(".googleapis.com", ""));
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Internal error");
        }
    }

    @Get("/projects/{project}/services")
    public HttpResponse listServices(@Param String project) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.set("services", RestResponseHelper.MAPPER.createArrayNode());
            result.put("nextPageToken", "");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Internal error");
        }
    }

    @Post("regex:^/projects/(?<project>[^/]+)/services/(?<service>[^:]+):enable$")
    public HttpResponse enableService(@Param String project, @Param String service) {
        try {
            ObjectNode operation = RestResponseHelper.MAPPER.createObjectNode();
            operation.put("name", "operations/su-" + System.currentTimeMillis());
            operation.put("done", true);
            ObjectNode resp = operation.putObject("response");
            resp.put("name", service);
            resp.put("state", "ENABLED");
            resp.put("@type", "type.googleapis.com/google.api.serviceusage.v1.EnableServiceResponse");
            return RestResponseHelper.ok(operation);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Internal error");
        }
    }

    @Get("/v1/projects/{project}/services/{service}/consumerQuotaMetrics")
    public HttpResponse getConsumerQuotaMetrics(@Param String project, @Param String service) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.set("consumerQuotaMetrics", RestResponseHelper.MAPPER.createArrayNode());
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Internal error");
        }
    }

    @Get("/v1/projects/{project}/services/{service}/consumerQuotaMetrics/{metric}/limits/{limit}/consumerOverrides")
    public HttpResponse getConsumerOverrides(@Param String project, @Param String service,
                                              @Param String metric, @Param String limit) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.set("consumerOverrides", RestResponseHelper.MAPPER.createArrayNode());
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Internal error");
        }
    }

    @Post("regex:^/projects/(?<project>[^/]+)/services:batchEnable$")
    public HttpResponse batchEnableServices(@Param String project, String body) {
        try {
            ObjectNode operation = RestResponseHelper.MAPPER.createObjectNode();
            operation.put("name", "operations/su-batch-" + System.currentTimeMillis());
            operation.put("done", true);
            ObjectNode resp = operation.putObject("response");
            resp.put("@type", "type.googleapis.com/google.api.serviceusage.v1.BatchEnableServicesResponse");
            ArrayNode services = resp.putArray("services");
            if (body != null && !body.isBlank()) {
                JsonNode parsed = RestResponseHelper.parseBody(body);
                if (parsed.has("serviceIds") && parsed.get("serviceIds").isArray()) {
                    for (JsonNode id : parsed.get("serviceIds")) {
                        ObjectNode svc = services.addObject();
                        svc.put("name", id.asText());
                        svc.put("state", "ENABLED");
                    }
                }
            }
            return RestResponseHelper.ok(operation);
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Internal error");
        }
    }
}
