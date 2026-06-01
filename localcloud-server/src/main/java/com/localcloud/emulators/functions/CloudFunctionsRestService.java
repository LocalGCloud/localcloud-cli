package com.localcloud.emulators.functions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.functions.v2.Function;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.common.RestResponseHelper;

import java.util.*;

/**
 * REST endpoints for Cloud Functions (2nd gen) management.
 */
public class CloudFunctionsRestService {

    private final CloudFunctionsRepository repo;
    private final CloudFunctionsEmulator emulator;

    public CloudFunctionsRestService(CloudFunctionsRepository repo, CloudFunctionsEmulator emulator) {
        this.repo = repo;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/locations/{location}/functions")
    public HttpResponse createFunction(ServiceRequestContext ctx, @Param String project,
                                       @Param String location, String body) {
        emulator.incrementRequestCount();
        try {
            var root = RestResponseHelper.parseBody(body);
            String functionId = ctx != null ? ctx.queryParams().get("functionId") : null;
            if (functionId == null || functionId.isBlank()) {
                functionId = root.path("name").asText(null);
                if (functionId != null && functionId.contains("/")) functionId = functionId.substring(functionId.lastIndexOf('/') + 1);
            }
            if (functionId == null || functionId.isBlank()) return RestResponseHelper.error(400, "Missing functionId");
            if (repo.exists(project, location, functionId)) return RestResponseHelper.error(409, "Function already exists");

            Function fn = Function.newBuilder()
                    .setName("projects/" + project + "/locations/" + location + "/functions/" + functionId)
                    .setState(Function.State.ACTIVE)
                    .build();
            repo.create(project, location, functionId, fn);

            ObjectNode op = RestResponseHelper.MAPPER.createObjectNode();
            op.put("name", "projects/" + project + "/locations/" + location + "/operations/" + UUID.randomUUID().toString().substring(0, 8));
            op.put("done", true);
            ObjectNode resp = op.putObject("response");
            resp.put("@type", "type.googleapis.com/google.cloud.functions.v2.Function");
            resp.put("name", fn.getName());
            resp.put("state", "ACTIVE");
            if (root.has("buildConfig")) resp.set("buildConfig", root.get("buildConfig"));
            if (root.has("serviceConfig")) resp.set("serviceConfig", root.get("serviceConfig"));
            if (root.has("labels")) resp.set("labels", root.get("labels"));
            return RestResponseHelper.ok(op);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/functions/{function}")
    public HttpResponse getFunction(@Param String project, @Param String location, @Param String function) {
        emulator.incrementRequestCount();
        try {
            Function fn = repo.get(project, location, function);
            if (fn == null) return RestResponseHelper.error(404, "Function not found");
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", fn.getName());
            result.put("state", "ACTIVE");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/locations/{location}/functions/{function}")
    public HttpResponse deleteFunction(@Param String project, @Param String location, @Param String function) {
        emulator.incrementRequestCount();
        try { repo.delete(project, location, function); } catch (Exception ignored) {}
        return HttpResponse.of(com.linecorp.armeria.common.HttpStatus.OK,
                com.linecorp.armeria.common.MediaType.JSON, "{}");
    }
}
