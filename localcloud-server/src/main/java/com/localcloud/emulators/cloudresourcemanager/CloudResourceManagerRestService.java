package com.localcloud.emulators.cloudresourcemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;

import com.localcloud.admin.ProjectService;
import com.localcloud.config.LocalCloudConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cloud Resource Manager API v3 facade for Terraform's {@code google_project} resource.
 * <p>
 * Wraps {@link ProjectService} with Google-compatible REST paths and response shapes.
 * All CRUD operations are served synchronously (no real LRO) — operations return
 * {@code done: true} immediately.
 * <p>
 * Routes:
 * <ul>
 *   <li>{@code POST   /v3/projects} — Create project</li>
 *   <li>{@code GET    /v3/projects} — List projects</li>
 *   <li>{@code GET    /v3/projects/{projectId}} — Get project</li>
 *   <li>{@code DELETE /v3/projects/{projectId}} — Delete project</li>
 *   <li>{@code PATCH  /v3/projects/{projectId}} — Update project</li>
 * </ul>
 */
public class CloudResourceManagerRestService {

    private static final Logger logger = LoggerFactory.getLogger(CloudResourceManagerRestService.class);

    private final ProjectService projectService;
    private final LocalCloudConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiVersion; // "v1" or "v3"

    public CloudResourceManagerRestService(ProjectService projectService, LocalCloudConfig config, String apiVersion) {
        this.projectService = projectService;
        this.config = config;
        this.apiVersion = apiVersion;
    }

    /**
     * Create a new project. Maps to Google Cloud Resource Manager POST /v3/projects.
     * <p>
     * Request body: { project_id, name, labels, parent }
     * Returns an Operation that resolves to a Project.
     */
    @Post("/projects")
    public HttpResponse createProject(String body) {
        try {
            JsonNode parsed = mapper.readTree(body);
            String projectId = extractProjectId(parsed);
            String displayName = parsed.has("name") ? parsed.get("name").asText() : projectId;

            if (projectId == null || projectId.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Missing required field: project_id");
            }

            String labelsJson = "{}";
            if (parsed.has("labels") && parsed.get("labels").isObject()) {
                labelsJson = mapper.writeValueAsString(parsed.get("labels"));
            }

            Map<String, Object> project = projectService.createProject(projectId, displayName, labelsJson);
            ObjectNode projectJson = toGoogleProject(project);
            ObjectNode operation = toOperation("create", projectId, projectJson);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(operation));
        } catch (Exception e) {
            logger.error("Error creating project", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * List all projects. Maps to Google Cloud Resource Manager GET /v3/projects.
     * <p>
     * Response: { projects: [Project], nextPageToken: "" }
     */
    @Get("/projects")
    public HttpResponse listProjects() {
        try {
            List<Map<String, Object>> projects = projectService.listProjects();
            ArrayNode projectsArray = mapper.createArrayNode();
            for (Map<String, Object> p : projects) {
                projectsArray.add(toGoogleProject(p));
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("projects", projectsArray);
            result.put("nextPageToken", "");

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error listing projects", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Get billing info for a project. Always returns enabled.
     * Maps to Google Cloud Billing GET /v1/projects/{projectId}/billingInfo
     */
    @Get("/operations/{operation}")
    public HttpResponse getOperation(@Param String operation) {
        try {
            // Operations are always done synchronously in LocalCloud
            Map<String, Object> project = null;
            // Operation names are of form "operations/xxxxx"
            // The project can be retrieved separately
            ObjectNode op = toDoneOperation(operation);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(op));
        } catch (Exception e) {
            logger.error("Error getting operation", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Get("/projects/{projectId}/billingInfo")
    public HttpResponse getBillingInfo(@Param String projectId) {
        try {
            Map<String, Object> project = projectService.getProject(projectId);
            ObjectNode result = mapper.createObjectNode();
            result.put("name", "projects/" + projectId + "/billingInfo");
            result.put("projectId", projectId);
            result.put("billingAccountName", "billingAccounts/localcloud-fake");
            result.put("billingEnabled", true);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting billing info", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Get a single project. Maps to Google Cloud Resource Manager GET /v3/projects/projects/{projectId}.
     */
    @Get("/projects/{projectId}")
    public HttpResponse getProject(@Param String projectId) {
        try {
            Map<String, Object> project = projectService.getProject(projectId);
            if (project == null) {
                return errorResponse(HttpStatus.NOT_FOUND,
                        "Project not found: projects/" + projectId);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(toGoogleProject(project)));
        } catch (Exception e) {
            logger.error("Error getting project '{}'", projectId, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Delete a project. Maps to Google Cloud Resource Manager DELETE /v3/projects/{projectId}.
     * Returns an Operation. Prevents deletion of the default project.
     */
    @Delete("/projects/{projectId}")
    public HttpResponse deleteProject(@Param String projectId) {
        try {
            projectService.deleteProject(projectId, config.getProjectId());
            ObjectNode operation = toDeleteOperation("delete", projectId);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(operation));
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (Exception e) {
            logger.error("Error deleting project '{}'", projectId, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Update a project. Maps to Google Cloud Resource Manager PATCH /v3/projects/{projectId}.
     * <p>
     * Supports updating displayName and labels. updateMask query param is ignored
     * (all provided fields are updated).
     */
    @Patch("/projects/{projectId}")
    public HttpResponse updateProject(@Param String projectId, String body) {
        try {
            JsonNode parsed = mapper.readTree(body);
            String displayName = parsed.has("name") ? parsed.get("name").asText() : null;
            String labelsJson = null;
            if (parsed.has("labels") && parsed.get("labels").isObject()) {
                labelsJson = mapper.writeValueAsString(parsed.get("labels"));
            }

            Map<String, Object> project = projectService.updateProject(projectId, displayName, labelsJson);
            if (project == null) {
                return errorResponse(HttpStatus.NOT_FOUND,
                        "Project not found: projects/" + projectId);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(toGoogleProject(project)));
        } catch (Exception e) {
            logger.error("Error updating project '{}'", projectId, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // --- Response shape helpers ---

    /**
     * Convert internal project map to Google Cloud Resource Manager v3 Project shape.
     * <pre>
     * {
     *   "name": "projects/my-project",
     *   "projectId": "my-project",
     *   "state": "ACTIVE",
     *   "displayName": "My Project",
     *   "labels": {"key": "value"},
     *   "createTime": "2024-01-01T00:00:00Z"
     * }
     * </pre>
     */
    private ObjectNode toGoogleProject(Map<String, Object> project) {
        return "v1".equals(apiVersion) ? toV1Project(project) : toV3Project(project);
    }

    /**
     * Build a v1-style Project response (used by the Terraform provider v6).
     * v1 field names: name (display name), lifecycleState, parent as {type, id} object.
     */
    private ObjectNode toV1Project(Map<String, Object> project) {
        String projectId = (String) project.get("project_id");
        String displayName = (String) project.get("display_name");
        String state = (String) project.getOrDefault("state", "ACTIVE");
        String labels = (String) project.get("labels");
        String createdAt = (String) project.get("created_at");
        String projectNumber = (String) project.get("project_number");

        ObjectNode node = mapper.createObjectNode();
        node.put("projectNumber", projectNumber != null ? projectNumber
                : String.valueOf(Math.abs(projectId.hashCode()) % 9_000_000_000_000L + 1_000_000_000_000L));
        node.put("projectId", projectId);
        // v1: "name" is the display name (v3 uses "name" as "projects/{id}")
        node.put("name", displayName != null ? displayName : projectId);
        // v1: lifecycleState instead of state
        String lifecycleState = "ACTIVE".equals(state) ? "ACTIVE" : state;
        node.put("lifecycleState", lifecycleState);
        node.put("createTime", createdAt != null ? createdAt : java.time.Instant.now().toString());
        try {
            node.set("labels", mapper.readTree(labels != null && !labels.isEmpty() && !labels.equals("{}") ? labels : "{}"));
        } catch (Exception e) {
            node.set("labels", mapper.createObjectNode());
        }
        // parent as {type, id} object — default to organization:0
        ObjectNode parent = mapper.createObjectNode();
        parent.put("type", "organization");
        parent.put("id", "0");
        node.set("parent", parent);
        // tags (may be empty)
        node.set("tags", mapper.createObjectNode());
        return node;
    }

    /**
     * Build a v3-style Project response.
     * v3 field names: name (resource name "projects/{id}"), state, displayName,
     * parent as string, updateTime, etag.
     */
    private ObjectNode toV3Project(Map<String, Object> project) {
        String projectId = (String) project.get("project_id");
        String displayName = (String) project.get("display_name");
        String state = (String) project.getOrDefault("state", "ACTIVE");
        String labels = (String) project.get("labels");
        String createdAt = (String) project.get("created_at");
        String updatedAt = (String) project.get("updated_at");
        String projectNumber = (String) project.get("project_number");

        ObjectNode node = mapper.createObjectNode();
        node.put("@type", "type.googleapis.com/google.cloud.resourcemanager.v3.Project");
        node.put("name", "projects/" + projectId);
        node.put("projectId", projectId);
        if (projectNumber == null || projectNumber.isBlank()) {
            projectNumber = String.valueOf(Math.abs(projectId.hashCode()) % 9_000_000_000_000L + 1_000_000_000_000L);
        }
        node.put("projectNumber", projectNumber);
        node.put("state", state);
        node.put("displayName", displayName != null ? displayName : projectId);
        // parent as string (e.g., "organizations/0")
        node.put("parent", "organizations/0");
        try {
            node.set("labels", mapper.readTree(labels != null && !labels.isEmpty() && !labels.equals("{}") ? labels : "{}"));
        } catch (Exception e) {
            node.set("labels", mapper.createObjectNode());
        }
        if (createdAt != null) {
            node.put("createTime", createdAt);
        }
        if (updatedAt != null) {
            node.put("updateTime", updatedAt);
        } else if (createdAt != null) {
            node.put("updateTime", createdAt);
        }
        // etag — a checksum for optimistic concurrency
        node.put("etag", Integer.toHexString(projectId.hashCode()));
        return node;
    }

    /**
     * Build a Google-style LRO Operation response for create/update.
     * <pre>
     * {
     *   "name": "operations/abc123",
     *   "metadata": { ... },
     *   "done": true,
     *   "response": { Project }
     * }
     * </pre>
     */
    /**
     * Build a done operation response for polling (no project response).
     */
    private ObjectNode toDoneOperation(String operationName) {
        ObjectNode operation = mapper.createObjectNode();
        operation.put("name", operationName);
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("@type", "type.googleapis.com/google.cloud.resourcemanager.v3.CreateProjectMetadata");
        metadata.put("createTime", Instant.now().toString());
        metadata.put("gettable", true);
        metadata.put("ready", true);
        operation.set("metadata", metadata);
        operation.put("done", true);
        return operation;
    }

    private ObjectNode toOperation(String verb, String projectId, ObjectNode projectJson) {
        ObjectNode operation = mapper.createObjectNode();
        operation.put("name", "operations/" + UUID.randomUUID().toString().substring(0, 8));
        ObjectNode metadata = mapper.createObjectNode();
        if ("v1".equals(apiVersion)) {
            // v1 CreateProjectMetadata: only createTime, gettable, ready
            metadata.put("createTime", Instant.now().toString());
            metadata.put("gettable", true);
            metadata.put("ready", true);
        } else {
            metadata.put("@type", "type.googleapis.com/google.cloud.resourcemanager.v3.CreateProjectMetadata");
            metadata.put("createTime", Instant.now().toString());
            metadata.put("verb", verb);
            metadata.put("gettable", true);
            metadata.put("ready", true);
        }
        operation.set("metadata", metadata);
        operation.put("done", true);
        if (!"v1".equals(apiVersion)) {
            projectJson.put("@type", "type.googleapis.com/google.cloud.resourcemanager.v3.Project");
        }
        operation.set("response", projectJson);
        return operation;
    }

    /**
     * Build a Google-style LRO Operation response for delete.
     * No response body needed for delete — just metadata.
     */
    private ObjectNode toDeleteOperation(String verb, String projectId) {
        ObjectNode operation = mapper.createObjectNode();
        operation.put("name", "operations/" + UUID.randomUUID().toString().substring(0, 8));
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("createTime", Instant.now().toString());
        metadata.put("verb", verb);
        ObjectNode target = mapper.createObjectNode();
        target.put("value", "projects/" + projectId);
        metadata.set("deleteTarget", target);
        operation.set("metadata", metadata);
        operation.put("done", true);
        return operation;
    }

    private String extractProjectId(JsonNode parsed) {
        if (parsed.has("project_id")) {
            return parsed.get("project_id").asText();
        }
        // Terraform google_project sends "projectId" (camelCase)
        if (parsed.has("projectId")) {
            return parsed.get("projectId").asText();
        }
        return null;
    }

    private HttpResponse errorResponse(HttpStatus status, String message) {
        try {
            ObjectNode error = mapper.createObjectNode();
            ObjectNode details = mapper.createObjectNode();
            details.put("code", status.code());
            details.put("message", message != null ? message : "");
            details.put("status", status.reasonPhrase());
            error.set("error", details);
            return HttpResponse.of(status, MediaType.JSON, mapper.writeValueAsString(error));
        } catch (Exception e) {
            return HttpResponse.of(status, MediaType.PLAIN_TEXT_UTF_8,
                    message != null ? message : status.reasonPhrase());
        }
    }
}
