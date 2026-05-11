package com.localcloud.emulators.cloudsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;

import java.util.Map;

/**
 * Cloud SQL Admin API REST facade. Register this under both /sql/v1beta4 and /sql/v1.
 */
public class CloudSqlRestService {

    private final CloudSqlStore store;
    private final CloudSqlEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();

    public CloudSqlRestService(CloudSqlStore store, CloudSqlEmulator emulator) {
        this.store = store;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/instances")
    public HttpResponse insertInstance(@Param String project, String body) {
        emulator.incrementRequestCount();
        try {
            JsonNode root = readTree(body);
            String name = required(root, "name");
            String region = text(root, "region", "us-central1");
            String databaseVersion = text(root, "databaseVersion", "POSTGRES_15");
            String tier = root.path("settings").path("tier").asText("db-custom-1-3840");
            String settingsJson = root.has("settings") ? mapper.writeValueAsString(root.get("settings")) : "{}";
            Map<String, Object> row = store.createInstance(project, name, region, databaseVersion, tier, settingsJson);
            String op = store.insertOperation(project, name, "CREATE", "projects/" + project + "/instances/" + name, "{}");
            return json(HttpStatus.OK, operationJson(project, op, row));
        } catch (Exception e) {
            return exception(e, "create instance");
        }
    }

    @Get("/projects/{project}/instances")
    public HttpResponse listInstances(@Param String project) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            out.put("kind", "sql#instancesList");
            ArrayNode items = out.putArray("items");
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
            return row == null ? error(HttpStatus.NOT_FOUND, "Cloud SQL instance not found: " + instance)
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
                return error(HttpStatus.NOT_FOUND, "Cloud SQL instance not found: " + instance);
            }
            String op = store.insertOperation(project, instance, "DELETE", "projects/" + project + "/instances/" + instance, "{}");
            return json(HttpStatus.OK, operationJson(project, op, Map.of("instance_id", instance)));
        } catch (Exception e) {
            return exception(e, "delete instance");
        }
    }

    @Post("/projects/{project}/instances/{instance}/databases")
    public HttpResponse insertDatabase(@Param String project, @Param String instance, String body) {
        emulator.incrementRequestCount();
        try {
            if (store.getInstance(project, instance) == null) {
                return error(HttpStatus.NOT_FOUND, "Cloud SQL instance not found: " + instance);
            }
            JsonNode root = readTree(body);
            String name = required(root, "name");
            Map<String, Object> row = store.createDatabase(project, instance, name,
                    text(root, "charset", "UTF8"), text(root, "collation", ""));
            String op = store.insertOperation(project, instance, "CREATE_DATABASE",
                    "projects/" + project + "/instances/" + instance + "/databases/" + name, "{}");
            return json(HttpStatus.OK, operationJson(project, op, row));
        } catch (Exception e) {
            return exception(e, "create database");
        }
    }

    @Get("/projects/{project}/instances/{instance}/databases")
    public HttpResponse listDatabases(@Param String project, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            out.put("kind", "sql#databasesList");
            ArrayNode items = out.putArray("items");
            for (Map<String, Object> row : store.listDatabases(project, instance)) {
                items.add(databaseJson(row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list databases");
        }
    }

    @Get("/projects/{project}/instances/{instance}/databases/{database}")
    public HttpResponse getDatabase(@Param String project, @Param String instance, @Param String database) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getDatabase(project, instance, database);
            return row == null ? error(HttpStatus.NOT_FOUND, "Cloud SQL database not found: " + database)
                    : json(HttpStatus.OK, databaseJson(row));
        } catch (Exception e) {
            return exception(e, "get database");
        }
    }

    @Delete("/projects/{project}/instances/{instance}/databases/{database}")
    public HttpResponse deleteDatabase(@Param String project, @Param String instance, @Param String database) {
        emulator.incrementRequestCount();
        try {
            if (!store.deleteDatabase(project, instance, database)) {
                return error(HttpStatus.NOT_FOUND, "Cloud SQL database not found: " + database);
            }
            String op = store.insertOperation(project, instance, "DELETE_DATABASE",
                    "projects/" + project + "/instances/" + instance + "/databases/" + database, "{}");
            return json(HttpStatus.OK, operationJson(project, op, Map.of("instance_id", instance)));
        } catch (Exception e) {
            return exception(e, "delete database");
        }
    }

    @Post("/projects/{project}/instances/{instance}/users")
    public HttpResponse insertUser(@Param String project, @Param String instance, String body) {
        emulator.incrementRequestCount();
        try {
            if (store.getInstance(project, instance) == null) {
                return error(HttpStatus.NOT_FOUND, "Cloud SQL instance not found: " + instance);
            }
            JsonNode root = readTree(body);
            String name = required(root, "name");
            Map<String, Object> row = store.createUser(project, instance, name,
                    text(root, "host", "%"), text(root, "password", null));
            String op = store.insertOperation(project, instance, "CREATE_USER",
                    "projects/" + project + "/instances/" + instance + "/users/" + name, "{}");
            return json(HttpStatus.OK, operationJson(project, op, row));
        } catch (Exception e) {
            return exception(e, "create user");
        }
    }

    @Get("/projects/{project}/instances/{instance}/users")
    public HttpResponse listUsers(@Param String project, @Param String instance) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            out.put("kind", "sql#usersList");
            ArrayNode items = out.putArray("items");
            for (Map<String, Object> row : store.listUsers(project, instance)) {
                items.add(userJson(row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list users");
        }
    }

    @Get("/projects/{project}/operations")
    public HttpResponse listOperations(@Param String project) {
        emulator.incrementRequestCount();
        try {
            ObjectNode out = mapper.createObjectNode();
            out.put("kind", "sql#operationsList");
            ArrayNode items = out.putArray("items");
            for (Map<String, Object> row : store.listOperations(project)) {
                items.add(operationJson(project, String.valueOf(row.get("operation_id")), row));
            }
            return json(HttpStatus.OK, out);
        } catch (Exception e) {
            return exception(e, "list operations");
        }
    }

    @Get("/projects/{project}/operations/{operation}")
    public HttpResponse getOperation(@Param String project, @Param String operation) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> row = store.getOperation(project, operation);
            return row == null ? error(HttpStatus.NOT_FOUND, "Operation not found: " + operation)
                    : json(HttpStatus.OK, operationJson(project, operation, row));
        } catch (Exception e) {
            return exception(e, "get operation");
        }
    }

    @Get("/projects/{project}/flags")
    public HttpResponse listFlags(@Param String project) {
        emulator.incrementRequestCount();
        ObjectNode out = mapper.createObjectNode();
        out.put("kind", "sql#flagsList");
        ArrayNode items = out.putArray("items");
        items.add(flag("cloudsql.enable_pgaudit", "BOOLEAN", "POSTGRES_15"));
        items.add(flag("max_connections", "INTEGER", "POSTGRES_15"));
        items.add(flag("sql_mode", "STRING", "MYSQL_8_0"));
        return json(HttpStatus.OK, out);
    }

    @Get("/projects/{project}/tiers")
    public HttpResponse listTiers(@Param String project) {
        emulator.incrementRequestCount();
        ObjectNode out = mapper.createObjectNode();
        out.put("kind", "sql#tiersList");
        ArrayNode items = out.putArray("items");
        items.add(tier("db-custom-1-3840", "CUSTOM", 1, 3840));
        items.add(tier("db-custom-2-7680", "CUSTOM", 2, 7680));
        return json(HttpStatus.OK, out);
    }

    private ObjectNode instanceJson(Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        String project = String.valueOf(row.get("project_id"));
        String instance = String.valueOf(row.get("instance_id"));
        String region = String.valueOf(row.get("region"));
        String databaseVersion = String.valueOf(row.get("database_version"));
        out.put("kind", "sql#instance");
        out.put("name", instance);
        out.put("project", project);
        out.put("region", region);
        out.put("databaseVersion", databaseVersion);
        out.put("state", String.valueOf(row.get("state")));
        out.put("connectionName", String.valueOf(row.get("connection_name")));
        out.put("backendType", String.valueOf(row.get("backend_type")));
        out.put("selfLink", "projects/" + project + "/instances/" + instance);
        ObjectNode settings = out.putObject("settings");
        settings.put("tier", String.valueOf(row.get("tier")));
        settings.put("dataDiskType", "PD_SSD");
        settings.put("activationPolicy", "ALWAYS");
        ObjectNode local = out.putObject("localcloud");
        local.put("postgresEndpoint", "localhost:5432");
        local.put("mysqlEndpoint", "localhost:3306");
        local.put("mysqlCompatibility", databaseVersion.startsWith("MYSQL") ? "requires-openhalo" : "not-applicable");
        local.put("dataPlaneStatus", databaseVersion.startsWith("MYSQL") ? "CONTROL_PLANE_ONLY_UNTIL_OPENHALO" : "CONTROL_PLANE_READY");
        return out;
    }

    private ObjectNode databaseJson(Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        out.put("kind", "sql#database");
        out.put("name", String.valueOf(row.get("database_name")));
        out.put("instance", String.valueOf(row.get("instance_id")));
        out.put("project", String.valueOf(row.get("project_id")));
        out.put("charset", String.valueOf(row.get("charset")));
        out.put("collation", String.valueOf(row.get("collation")));
        out.putObject("localcloud").put("physicalName", String.valueOf(row.get("physical_name")));
        return out;
    }

    private ObjectNode userJson(Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        out.put("kind", "sql#user");
        out.put("name", String.valueOf(row.get("user_name")));
        out.put("instance", String.valueOf(row.get("instance_id")));
        out.put("project", String.valueOf(row.get("project_id")));
        out.put("host", String.valueOf(row.get("host")));
        return out;
    }

    private ObjectNode operationJson(String project, String operation, Map<String, Object> row) {
        ObjectNode out = mapper.createObjectNode();
        out.put("kind", "sql#operation");
        out.put("name", operation);
        out.put("project", project);
        out.put("operationType", String.valueOf(row.getOrDefault("operation_type", "UNKNOWN")));
        out.put("status", String.valueOf(row.getOrDefault("status", "DONE")));
        if (row.get("target_link") != null) out.put("targetLink", String.valueOf(row.get("target_link")));
        if (row.get("instance_id") != null) out.put("targetId", String.valueOf(row.get("instance_id")));
        return out;
    }

    private ObjectNode flag(String name, String type, String appliesTo) {
        ObjectNode out = mapper.createObjectNode();
        out.put("name", name);
        out.put("type", type);
        out.putArray("appliesTo").add(appliesTo);
        return out;
    }

    private ObjectNode tier(String tier, String regionType, int cpu, int memoryMb) {
        ObjectNode out = mapper.createObjectNode();
        out.put("tier", tier);
        out.put("region", "us-central1");
        out.put("RAM", memoryMb);
        out.put("DiskQuota", 102400);
        out.put("regionType", regionType);
        out.put("cpu", cpu);
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
        inner.put("status", status.reasonPhrase().replace(' ', '_').toUpperCase());
        return HttpResponse.of(status, MediaType.JSON, out.toString());
    }
}
