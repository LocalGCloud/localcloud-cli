package com.localcloud.emulators.bigtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.localcloud.admin.BigtableGrpcClient;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;

/**
 * REST facade for the Bigtable emulator (little_bigtable).
 * All operations are proxied to the emulator via gRPC.
 * No PostgreSQL dependency — the emulator handles its own persistence.
 */
public class BigtableAdminService {

    private final int emulatorPort;
    private final BigtableEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final IAMPolicyRestHandler iamHandler;

    public BigtableAdminService(int emulatorPort, BigtableEmulator emulator) {
        this(emulatorPort, emulator, null);
    }

    public BigtableAdminService(int emulatorPort, BigtableEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.emulatorPort = emulatorPort;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
    }

    @Post("/projects/{project}/instances")
    public HttpResponse createInstance(@Param String project, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            String instanceId = required(root, "instanceId");
            String displayName = text(root, "displayName", instanceId);
            String instanceType = text(root, "instanceType", "PRODUCTION");
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                client.ensureInstance(project, instanceId, displayName, instanceType);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project_id", project);
            row.put("instance_id", instanceId);
            row.put("display_name", displayName);
            row.put("instance_type", instanceType);
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
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                for (Map<String, Object> inst : client.listInstancesWithDetails(project)) {
                    ObjectNode node = mapper.createObjectNode();
                    String instId = String.valueOf(inst.getOrDefault("id", inst.get("name")));
                    node.put("name", "projects/" + project + "/instances/" + instId);
                    node.put("displayName", String.valueOf(inst.getOrDefault("displayName", instId)));
                    node.put("type", String.valueOf(inst.getOrDefault("instanceType",
                            inst.getOrDefault("type", "PRODUCTION"))));
                    node.put("state", String.valueOf(inst.getOrDefault("state", "READY")));
                    items.add(node);
                }
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
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                Map<String, Object> row = client.getInstance(project, instance);
                if (row == null) {
                    return error(HttpStatus.NOT_FOUND, "Bigtable instance not found: " + instance);
                }
                ObjectNode node = mapper.createObjectNode();
                node.put("name", "projects/" + project + "/instances/" + instance);
                node.put("displayName", String.valueOf(row.getOrDefault("displayName", instance)));
                node.put("type", String.valueOf(row.getOrDefault("instanceType", "PRODUCTION")));
                node.put("state", String.valueOf(row.getOrDefault("state", "READY")));
                return json(HttpStatus.OK, node);
            }
        } catch (Exception e) {
            return exception(e, "get instance");
        }
    }

    @Delete("/projects/{project}/instances/{instance}")
    public HttpResponse deleteInstance(@Param String project, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                client.deleteInstance(project, instance);
            } catch (io.grpc.StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    return error(HttpStatus.NOT_FOUND, "Bigtable instance not found: " + instance);
                }
                throw e;
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
            JsonNode root = readTree(body);
            String tableId = required(root, "tableId");
            List<String> families = new ArrayList<>();
            if (root.has("columnFamilies") && root.get("columnFamilies").isArray()) {
                for (JsonNode f : root.get("columnFamilies")) {
                    if (f.isTextual()) families.add(f.asText());
                    else if (f.isObject() && f.has("name")) families.add(f.get("name").asText());
                }
            }
            if (families.isEmpty()) {
                families.add("cf1");
            }
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                if (client.getInstance(project, instance) == null) {
                    return error(HttpStatus.NOT_FOUND, "Bigtable instance not found: " + instance);
                }
                client.ensureTable(project, instance, tableId, families, text(root, "granularity", "MILLIS"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project_id", project);
            row.put("instance_id", instance);
            row.put("table_id", tableId);
            row.put("granularity", text(root, "granularity", "MILLIS"));
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
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                for (Map<String, Object> t : client.listTablesForInstance(project, instance)) {
                    ObjectNode node = mapper.createObjectNode();
                    String tid = String.valueOf(t.get("table"));
                    node.put("name", "projects/" + project + "/instances/" + instance + "/tables/" + tid);
                    node.put("granularity", String.valueOf(t.getOrDefault("granularity", "MILLIS")));
                    items.add(node);
                }
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
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                List<String> families = client.getColumnFamilies(project, instance, table);
                ObjectNode node = mapper.createObjectNode();
                node.put("name", "projects/" + project + "/instances/" + instance + "/tables/" + table);
                node.put("granularity", "MILLIS");
                ArrayNode cfArray = node.putArray("columnFamilies");
                for (String f : families) {
                    cfArray.add(f);
                }
                return json(HttpStatus.OK, node);
            } catch (io.grpc.StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    return error(HttpStatus.NOT_FOUND, "Bigtable table not found: " + table);
                }
                throw e;
            }
        } catch (Exception e) {
            return exception(e, "get table");
        }
    }

    @Delete("/projects/{project}/instances/{instance}/tables/{table}")
    public HttpResponse deleteTable(@Param String project, @Param String instance, @Param String table) {
        emulator.incrementRequestCount();
        try {
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                client.deleteTable(project, instance, table);
            } catch (io.grpc.StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    return error(HttpStatus.NOT_FOUND, "Bigtable table not found: " + table);
                }
                throw e;
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
            List<String> addFamilies = new ArrayList<>();
            List<String> dropFamilies = new ArrayList<>();
            if (root.has("modifications") && root.get("modifications").isArray()) {
                for (JsonNode mod : root.get("modifications")) {
                    String id = mod.has("id") ? mod.get("id").asText() : null;
                    if (id == null) continue;
                    if (mod.has("drop") && (mod.get("drop").asBoolean() || "true".equalsIgnoreCase(mod.get("drop").asText()))) {
                        dropFamilies.add(id);
                    } else {
                        addFamilies.add(id);
                    }
                }
            }
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                client.modifyColumnFamilies(project, instance, table, addFamilies, dropFamilies);
            }
            return json(HttpStatus.OK, mapper.createObjectNode().put("status", "updated"));
        } catch (Exception e) {
            return exception(e, "modify column families");
        }
    }

    // --- Row-level data operations ---

    /**
     * Read rows from a table. Optional query params: limit (default 100, max 1000).
     * Returns an array of row objects with "rowKey" and "cells" fields.
     */
    @Get("/projects/{project}/instances/{instance}/tables/{table}/rows")
    public HttpResponse readRows(@Param String project, @Param String instance,
                                  @Param String table, @Param @Default("100") int limit) {
        emulator.incrementRequestCount();
        if (limit <= 0) limit = 100;
        limit = Math.min(limit, 1000);
        try {
            ObjectNode out = mapper.createObjectNode();
            ArrayNode rows = out.putArray("rows");
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                for (Map<String, Object> row : client.readRows(project, instance, table, limit)) {
                    ObjectNode rowNode = mapper.createObjectNode();
                    rowNode.put("rowKey", String.valueOf(row.get("rowKey")));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cells = (Map<String, Object>) row.get("cells");
                    if (cells != null) {
                        ObjectNode cellsNode = rowNode.putObject("cells");
                        for (Map.Entry<String, Object> entry : cells.entrySet()) {
                            cellsNode.put(entry.getKey(), decodeCellValue(entry.getValue()));
                        }
                    }
                    rows.add(rowNode);
                }
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "read rows");
        }
    }

    /**
     * Decode a Bigtable cell value (byte array) to a UTF-8 string for JSON output.
     */
    private String decodeCellValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    /**
     * Write cells to a row. Body: { "rowKey": "...", "cells": { "cf1:col1": "value1" } }
     * The target table must already exist (created via instance/table admin endpoints).
     */
    @Post("/projects/{project}/instances/{instance}/tables/{table}/rows")
    public HttpResponse mutateRow(@Param String project, @Param String instance,
                                   @Param String table, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            String rowKey = required(root, "rowKey");
            Map<String, Object> cells = new LinkedHashMap<>();
            if (root.has("cells") && root.get("cells").isObject()) {
                root.get("cells").fields().forEachRemaining(e ->
                    cells.put(e.getKey(), e.getValue().asText()));
            }
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("Missing required field: cells");
            }
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                client.mutateRow(project, instance, table, rowKey, cells);
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("status", "written");
            out.put("rowKey", rowKey);
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "mutate row");
        }
    }

    /**
     * Delete a single row by key.
     */
    @Delete("/projects/{project}/instances/{instance}/tables/{table}/rows/{rowKey}")
    public HttpResponse deleteRow(@Param String project, @Param String instance,
                                   @Param String table, @Param String rowKey) {
        emulator.incrementRequestCount();
        try {
            try (BigtableGrpcClient client = new BigtableGrpcClient(emulatorPort)) {
                client.deleteRow(project, instance, table, rowKey);
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("status", "deleted");
            out.put("rowKey", rowKey);
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "delete row");
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
