package com.localcloud.emulators.bigtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;

import java.util.Map;

public class BigtableAdminService {

    private final BigtableStore store;
    private final BigtableEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();

    public BigtableAdminService(BigtableStore store, BigtableEmulator emulator) {
        this.store = store;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/instances")
    public HttpResponse createInstance(@Param String project, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            String instanceId = required(root, "instanceId");
            String displayName = text(root, "displayName", instanceId);
            String instanceType = text(root, "instanceType", "PRODUCTION");
            String clustersJson = root.has("clusters") ? mapper.writeValueAsString(root.get("clusters")) : "[]";
            Map<String, Object> row = store.createInstance(project, instanceId, displayName, instanceType, clustersJson);
            return json(HttpStatus.OK, instanceJson(row));
        } catch (Exception e) {
            return exception(e, "create instance");
        }
    }

    @Get("/projects/{project}/instances")
    public HttpResponse listInstances(@Param String project) {
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

    @Get("/projects/{project}/instances/{instance}")
    public HttpResponse getInstance(@Param String project, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getInstance(project, instance);
            return row == null ? error(HttpStatus.NOT_FOUND, "Bigtable instance not found: " + instance)
                    : json(HttpStatus.OK, instanceJson(row));
        } catch (Exception e) {
            return exception(e, "get instance");
        }
    }

    @Delete("/projects/{project}/instances/{instance}")
    public HttpResponse deleteInstance(@Param String project, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            if (!store.deleteInstance(project, instance)) {
                return error(HttpStatus.NOT_FOUND, "Bigtable instance not found: " + instance);
            }
            return json(HttpStatus.OK, mapper.createObjectNode()
                    .put("status", "deleted")
                    .put("instance", instance));
        } catch (Exception e) {
            return exception(e, "delete instance");
        }
    }

    @Post("/projects/{project}/instances/{instance}/tables")
    public HttpResponse createTable(@Param String project, @Param String instance, String body) {
        emulator.incrementRequestCount();
        try {
            if (store.getInstance(project, instance) == null) {
                return error(HttpStatus.NOT_FOUND, "Bigtable instance not found: " + instance);
            }
            JsonNode root = readTree(body);
            String tableId = required(root, "tableId");
            String granularity = text(root, "granularity", "MILLIS");
            String columnFamiliesJson = root.has("columnFamilies") ? mapper.writeValueAsString(root.get("columnFamilies")) : "[]";
            Map<String, Object> row = store.createTable(project, instance, tableId, columnFamiliesJson, granularity);
            return json(HttpStatus.OK, tableJson(row));
        } catch (Exception e) {
            return exception(e, "create table");
        }
    }

    @Get("/projects/{project}/instances/{instance}/tables")
    public HttpResponse listTables(@Param String project, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode items = out.putArray("tables");
            for (Map<String, Object> row : store.listTables(project, instance)) {
                items.add(tableJson(row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list tables");
        }
    }

    @Get("/projects/{project}/instances/{instance}/tables/{table}")
    public HttpResponse getTable(@Param String project, @Param String instance, @Param String table) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getTable(project, instance, table);
            return row == null ? error(HttpStatus.NOT_FOUND, "Bigtable table not found: " + table)
                    : json(HttpStatus.OK, tableJson(row));
        } catch (Exception e) {
            return exception(e, "get table");
        }
    }

    @Delete("/projects/{project}/instances/{instance}/tables/{table}")
    public HttpResponse deleteTable(@Param String project, @Param String instance, @Param String table) {
        emulator.incrementRequestCount();
        try {
            if (!store.deleteTable(project, instance, table)) {
                return error(HttpStatus.NOT_FOUND, "Bigtable table not found: " + table);
            }
            return json(HttpStatus.OK, mapper.createObjectNode()
                    .put("status", "deleted")
                    .put("table", table));
        } catch (Exception e) {
            return exception(e, "delete table");
        }
    }

    // Route registered manually via regex in LocalCloudApplication (colon in path breaks annotation parser)
    public HttpResponse modifyColumnFamilies(@Param String project, @Param String instance, @Param String table, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            String columnFamiliesJson = root.has("columnFamilies") ? mapper.writeValueAsString(root.get("columnFamilies")) : "[]";
            if (!store.modifyColumnFamilies(project, instance, table, columnFamiliesJson)) {
                return error(HttpStatus.NOT_FOUND, "Bigtable table not found: " + table);
            }
            return json(HttpStatus.OK, mapper.createObjectNode().put("status", "updated"));
        } catch (Exception e) {
            return exception(e, "modify column families");
        }
    }

    private ObjectNode instanceJson(Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        String project = String.valueOf(row.get("project_id"));
        String instance = String.valueOf(row.get("instance_id"));
        out.put("name", "projects/" + project + "/instances/" + instance);
        out.put("displayName", String.valueOf(row.get("display_name")));
        out.put("type", String.valueOf(row.get("instance_type")));
        out.put("state", String.valueOf(row.get("state")));
        return out;
    }

    private ObjectNode tableJson(Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        String project = String.valueOf(row.get("project_id"));
        String instance = String.valueOf(row.get("instance_id"));
        String table = String.valueOf(row.get("table_id"));
        out.put("name", "projects/" + project + "/instances/" + instance + "/tables/" + table);
        out.put("granularity", String.valueOf(row.get("granularity")));
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

    private HttpResponse json(HttpStatus status, JsonNode node) {
        return HttpResponse.of(status, MediaType.JSON, node.toString());
    }

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
