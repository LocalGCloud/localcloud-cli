package com.localcloud.emulators.memorystore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;

import java.util.Map;

import com.localcloud.emulators.iam.IAMPolicyRestHandler;

public class MemorystoreAdminService {

    private final MemorystoreStore store;
    private final MemorystoreEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final IAMPolicyRestHandler iamHandler;

    public MemorystoreAdminService(MemorystoreStore store, MemorystoreEmulator emulator) {
        this(store, emulator, null);
    }

    public MemorystoreAdminService(MemorystoreStore store, MemorystoreEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.store = store;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
    }

    @Post("/projects/{project}/locations/{location}/instances")
    public HttpResponse createInstance(@Param String project, @Param String location, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            String instanceId = idFromName(required(root, "name"));
            String displayName = text(root, "displayName", instanceId);
            String tier = text(root, "tier", "BASIC");
            String redisVersion = text(root, "redisVersion", "7_0");
            int memorySizeGb = root.has("memorySizeGb") ? root.get("memorySizeGb").asInt(1) : 1;
            Map<String, Object> row = store.createInstance(project, instanceId, displayName, tier, "REDIS", redisVersion, 6379, memorySizeGb);
            // Return an operation (LRO pattern) instead of the instance directly.
            // Terraform provider polls GET /operations/{name} until done=true.
            String opName = "projects/" + project + "/locations/" + location + "/operations/" + instanceId;
            ObjectNode op = mapper.createObjectNode();
            op.put("name", opName);
            op.put("done", true);
            op.set("response", instanceJson(row));
            return json(HttpStatus.OK, op);
        } catch (Exception e) {
            return exception(e, "create instance");
        }
    }

    @Get("/projects/{project}/locations/{location}/operations/{operation}")
    public HttpResponse getOperation(@Param String project, @Param String location, @Param String operation) {
        emulator.incrementRequestCount();
        try {
            // Operation ID is the instance ID
            Map<String, Object> row = store.getInstance(project, operation);
            if (row == null) {
                return error(HttpStatus.NOT_FOUND, "Operation not found: " + operation);
            }
            String opName = "projects/" + project + "/locations/" + location + "/operations/" + operation;
            ObjectNode op = mapper.createObjectNode();
            op.put("name", opName);
            op.put("done", true);
            op.set("response", instanceJson(row));
            return json(HttpStatus.OK, op);
        } catch (Exception e) {
            return exception(e, "get operation");
        }
    }

    @Get("/projects/{project}/locations/{location}/instances")
    public HttpResponse listInstances(@Param String project, @Param String location) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode items = out.putArray("instances");
            for (Map<String, Object> row : store.listInstances(project)) {
                items.add(instanceJson(row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list instances");
        }
    }

    @Get("/projects/{project}/locations/{location}/instances/{instance}")
    public HttpResponse getInstance(@Param String project, @Param String location, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getInstance(project, instance);
            return row == null ? error(HttpStatus.NOT_FOUND, "Memorystore instance not found: " + instance)
                    : json(HttpStatus.OK, instanceJson(row));
        } catch (Exception e) {
            return exception(e, "get instance");
        }
    }

    @Delete("/projects/{project}/locations/{location}/instances/{instance}")
    public HttpResponse deleteInstance(@Param String project, @Param String location, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            if (!store.deleteInstance(project, instance)) {
                return error(HttpStatus.NOT_FOUND, "Memorystore instance not found: " + instance);
            }
            return json(HttpStatus.OK, mapper.createObjectNode()
                    .put("status", "deleted")
                    .put("instance", instance));
        } catch (Exception e) {
            return exception(e, "delete instance");
        }
    }

    private ObjectNode instanceJson(Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        String project = String.valueOf(row.get("project_id"));
        String instance = String.valueOf(row.get("instance_id"));
        String location = "us-central1";
        out.put("name", "projects/" + project + "/locations/" + location + "/instances/" + instance);
        out.put("displayName", String.valueOf(row.get("display_name")));
        out.put("tier", String.valueOf(row.get("tier")));
        out.put("redisVersion", String.valueOf(row.get("redis_version")));
        out.put("memorySizeGb", String.valueOf(row.get("memory_size_gb")));
        out.put("state", String.valueOf(row.get("state")));
        out.put("host", String.valueOf(row.get("host")));
        out.put("port", String.valueOf(row.get("port")));
        return out;
    }

    private JsonNode readTree(String body) throws Exception {
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private String required(JsonNode node, String field) {
        String value = text(node, field, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing required field: " + field);
        return value;
    }

    private String text(JsonNode node, String field, String defaultValue) {
        return node != null && node.has(field) && !node.get(field).isNull() ? node.get(field).asText(defaultValue) : defaultValue;
    }

    private String idFromName(String name) {
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private HttpResponse json(HttpStatus status, JsonNode node) {
        return HttpResponse.of(status, MediaType.JSON, node.toString());
    }

    // IAM Policy endpoints are handled by the generic catch-all in LocalCloudApplication.

    private HttpResponse exception(Exception e, String action) {
        HttpStatus status = e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, "Failed to " + action + ": " + e.getMessage());
    }

    private HttpResponse error(HttpStatus status, String message) {
        ObjectNode out = mapper.createObjectNode();
        ObjectNode inner = out.putObject("error");
        inner.put("code", status.code());
        inner.put("message", message);
        return HttpResponse.of(status, MediaType.JSON, out.toString());
    }
}
