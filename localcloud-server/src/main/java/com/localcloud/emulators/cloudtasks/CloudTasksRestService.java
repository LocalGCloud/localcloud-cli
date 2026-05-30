package com.localcloud.emulators.cloudtasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.localcloud.emulators.iam.IAMPolicyRestHandler;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for Cloud Tasks matching the Google Cloud API surface.
 * Terraform's google_cloud_tasks_queue resource calls these paths.
 *
 * Routes: /v2/projects/{project}/locations/{location}/queues[/{queue}]
 */
public class CloudTasksRestService {

    private static final Logger logger = LoggerFactory.getLogger(CloudTasksRestService.class);

    private final CloudTasksStore store;
    private final CloudTasksEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final IAMPolicyRestHandler iamHandler;

    public CloudTasksRestService(CloudTasksStore store, CloudTasksEmulator emulator) {
        this(store, emulator, null);
    }

    public CloudTasksRestService(CloudTasksStore store, CloudTasksEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.store = store;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
    }

    @Post("/projects/{project}/locations/{location}/queues")
    public HttpResponse createQueue(@Param String project, @Param String location, String body) {
        emulator.incrementRequestCount();
        try {
            String queueId = null;
            try {
                var parsed = mapper.readTree(body);
                if (parsed.has("name")) {
                    String name = parsed.get("name").asText();
                    // Extract queue ID from full name: projects/P/locations/L/queues/Q
                    queueId = name.substring(name.lastIndexOf('/') + 1);
                }
            } catch (Exception e) {
                logger.debug("Failed to parse request body as JSON", e);
                return errorResponse(400, "Invalid JSON in request body");
            }

            if (queueId == null || queueId.isBlank()) {
                return errorResponse(400, "Missing queue name in request body");
            }

            store.createQueue(project, location, queueId);

            ObjectNode result = mapper.createObjectNode();
            result.put("name", "projects/" + project + "/locations/" + location + "/queues/" + queueId);
            result.put("state", "RUNNING");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                return errorResponse(409, "Queue already exists");
            }
            logger.error("Error creating queue", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/queues/{queue}")
    public HttpResponse getQueue(@Param String project, @Param String location, @Param String queue) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> q = store.getQueue(project, location, queue);
            if (q == null) {
                return errorResponse(404, "Queue not found: " + queue);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("name", "projects/" + project + "/locations/" + location + "/queues/" + queue);
            result.put("state", String.valueOf(q.getOrDefault("state", "RUNNING")));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting queue", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/queues")
    public HttpResponse listQueues(@Param String project, @Param String location) {
        emulator.incrementRequestCount();
        try {
            List<Map<String, Object>> queues = store.listQueues(project, location);
            ArrayNode queuesArray = mapper.createArrayNode();
            for (Map<String, Object> q : queues) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", "projects/" + project + "/locations/" + location + "/queues/" + q.get("queue_id"));
                node.put("state", String.valueOf(q.getOrDefault("state", "RUNNING")));
                queuesArray.add(node);
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("queues", queuesArray);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error listing queues", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/locations/{location}/queues/{queue}")
    public HttpResponse deleteQueue(@Param String project, @Param String location, @Param String queue) {
        emulator.incrementRequestCount();
        try {
            boolean deleted = store.deleteQueue(project, location, queue);
            if (!deleted) {
                return errorResponse(404, "Queue not found: " + queue);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}");
        } catch (Exception e) {
            logger.error("Error deleting queue", e);
            return errorResponse(500, e.getMessage());
        }
    }

    // IAM Policy endpoints are handled by the generic catch-all in LocalCloudApplication.

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
