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
            CloudTasksStore.QueueConfig config = parseQueueConfigFromBody(body);
            if (config.queueId == null || config.queueId.isBlank()) {
                return errorResponse(400, "Missing queue name in request body");
            }

            store.createQueue(project, location, config.queueId, config);

            CloudTasksStore.QueueConfig created = store.getQueueConfig(project, location, config.queueId);
            ObjectNode result = buildQueueJson(project, location, config.queueId, created);
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
            CloudTasksStore.QueueConfig config = store.getQueueConfig(project, location, queue);
            if (config == null) {
                return errorResponse(404, "Queue not found: " + queue);
            }
            ObjectNode result = buildQueueJson(project, location, queue, config);
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
                String queueId = (String) q.get("queue_id");
                CloudTasksStore.QueueConfig config = mapToConfig(q);
                ObjectNode node = buildQueueJson(project, location, queueId, config);
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

    @Patch("/projects/{project}/locations/{location}/queues/{queue}")
    public HttpResponse updateQueue(@Param String project, @Param String location,
                                    @Param String queue, String body) {
        emulator.incrementRequestCount();
        try {
            if (!store.queueExists(project, location, queue)) {
                return errorResponse(404, "Queue not found: " + queue);
            }

            // Read current config first, then merge only fields present in the JSON body
            CloudTasksStore.QueueConfig existing = store.getQueueConfig(project, location, queue);
            CloudTasksStore.QueueConfig updates = parseQueueConfigFromBody(body);

            // Merge: only apply fields that were explicitly provided in the body
            var parsed = mapper.readTree(body);
            if (parsed.has("state")) existing.state = updates.state;

            if (parsed.has("rateLimits")) {
                var rl = parsed.get("rateLimits");
                if (rl.has("maxDispatchesPerSecond")) existing.maxDispatchesPerSecond = updates.maxDispatchesPerSecond;
                if (rl.has("maxConcurrentDispatches")) existing.maxConcurrentDispatches = updates.maxConcurrentDispatches;
                if (rl.has("maxBurstSize")) existing.maxBurstSize = updates.maxBurstSize;
            }

            if (parsed.has("retryConfig")) {
                var rc = parsed.get("retryConfig");
                if (rc.has("maxAttempts")) existing.maxAttempts = updates.maxAttempts;
                if (rc.has("minBackoff")) existing.minBackoff = updates.minBackoff;
                if (rc.has("maxBackoff")) existing.maxBackoff = updates.maxBackoff;
                if (rc.has("maxDoublings")) existing.maxDoublings = updates.maxDoublings;
                if (rc.has("maxRetryDuration")) existing.maxRetryDuration = updates.maxRetryDuration;
            }

            if (parsed.has("httpTarget")) {
                existing.httpTargetUri = updates.httpTargetUri;
                existing.httpTargetMethod = updates.httpTargetMethod;
            }

            store.updateQueue(project, location, queue, existing);

            CloudTasksStore.QueueConfig updated = store.getQueueConfig(project, location, queue);
            ObjectNode result = buildQueueJson(project, location, queue, updated);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error updating queue", e);
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

    @Post("/projects/{project}/locations/{location}/queues/{queue}:purge")
    public HttpResponse purgeQueue(@Param String project, @Param String location, @Param String queue) {
        emulator.incrementRequestCount();
        try {
            if (!store.queueExists(project, location, queue)) {
                return errorResponse(404, "Queue not found: " + queue);
            }

            store.purgeQueue(project, location, queue);

            CloudTasksStore.QueueConfig config = store.getQueueConfig(project, location, queue);
            ObjectNode result = buildQueueJson(project, location, queue, config);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error purging queue", e);
            return errorResponse(500, e.getMessage());
        }
    }

    // --- Helpers ---

    private CloudTasksStore.QueueConfig parseQueueConfigFromBody(String body) throws Exception {
        CloudTasksStore.QueueConfig config = new CloudTasksStore.QueueConfig();

        if (body == null || body.isBlank()) return config;

        var parsed = mapper.readTree(body);

        if (parsed.has("name")) {
            String name = parsed.get("name").asText();
            config.queueId = name.substring(name.lastIndexOf('/') + 1);
        }

        if (parsed.has("state")) {
            config.state = parsed.get("state").asText();
        }

        // Rate limits
        if (parsed.has("rateLimits")) {
            var rl = parsed.get("rateLimits");
            if (rl.has("maxDispatchesPerSecond")) config.maxDispatchesPerSecond = rl.get("maxDispatchesPerSecond").asDouble();
            if (rl.has("maxConcurrentDispatches")) config.maxConcurrentDispatches = rl.get("maxConcurrentDispatches").asInt();
            if (rl.has("maxBurstSize")) config.maxBurstSize = rl.get("maxBurstSize").asInt();
        }

        // Retry config
        if (parsed.has("retryConfig")) {
            var rc = parsed.get("retryConfig");
            if (rc.has("maxAttempts")) config.maxAttempts = rc.get("maxAttempts").asInt();
            if (rc.has("minBackoff")) config.minBackoff = rc.get("minBackoff").asText();
            if (rc.has("maxBackoff")) config.maxBackoff = rc.get("maxBackoff").asText();
            if (rc.has("maxDoublings")) config.maxDoublings = rc.get("maxDoublings").asInt();
            if (rc.has("maxRetryDuration")) config.maxRetryDuration = rc.get("maxRetryDuration").asText();
        }

        // HTTP target (queue-level defaults)
        if (parsed.has("httpTarget")) {
            var ht = parsed.get("httpTarget");
            if (ht.has("uriOverride") && ht.get("uriOverride").has("path")) {
                config.httpTargetUri = ht.get("uriOverride").get("path").asText();
            }
            if (ht.has("httpMethod")) {
                config.httpTargetMethod = ht.get("httpMethod").asText();
            }
        }

        // Top-level convenience fields

        return config;
    }

    private ObjectNode buildQueueJson(String project, String location, String queueId,
                                       CloudTasksStore.QueueConfig config) {
        ObjectNode result = mapper.createObjectNode();
        result.put("name", "projects/" + project + "/locations/" + location + "/queues/" + queueId);
        result.put("state", config.state != null ? config.state : "RUNNING");

        // Rate limits
        ObjectNode rateLimits = mapper.createObjectNode();
        rateLimits.put("maxDispatchesPerSecond", config.maxDispatchesPerSecond);
        rateLimits.put("maxBurstSize", config.maxBurstSize);
        rateLimits.put("maxConcurrentDispatches", config.maxConcurrentDispatches);
        result.set("rateLimits", rateLimits);

        // Retry config
        ObjectNode retryConfig = mapper.createObjectNode();
        retryConfig.put("maxAttempts", config.maxAttempts);
        if (config.minBackoff != null) retryConfig.put("minBackoff", config.minBackoff);
        if (config.maxBackoff != null) retryConfig.put("maxBackoff", config.maxBackoff);
        retryConfig.put("maxDoublings", config.maxDoublings);
        if (config.maxRetryDuration != null) retryConfig.put("maxRetryDuration", config.maxRetryDuration);
        result.set("retryConfig", retryConfig);

        // HTTP target (if configured)
        if (config.httpTargetUri != null && !config.httpTargetUri.isEmpty()) {
            ObjectNode httpTarget = mapper.createObjectNode();
            ObjectNode uriOverride = mapper.createObjectNode();
            uriOverride.put("path", config.httpTargetUri);
            httpTarget.set("uriOverride", uriOverride);
            if (config.httpTargetMethod != null && !config.httpTargetMethod.isEmpty()) {
                httpTarget.put("httpMethod", config.httpTargetMethod);
            }
            result.set("httpTarget", httpTarget);
        }

        return result;
    }

    private CloudTasksStore.QueueConfig mapToConfig(Map<String, Object> row) {
        CloudTasksStore.QueueConfig config = new CloudTasksStore.QueueConfig();
        config.projectId = (String) row.get("project_id");
        config.locationId = (String) row.get("location_id");
        config.queueId = (String) row.get("queue_id");
        config.state = (String) row.get("state");
        Object mdp = row.get("max_dispatches_per_second");
        if (mdp instanceof Number) config.maxDispatchesPerSecond = ((Number) mdp).doubleValue();
        Object mcd = row.get("max_concurrent_dispatches");
        if (mcd instanceof Number) config.maxConcurrentDispatches = ((Number) mcd).intValue();
        Object mbs = row.get("max_burst_size");
        if (mbs instanceof Number) config.maxBurstSize = ((Number) mbs).intValue();
        Object ma = row.get("max_attempts");
        if (ma instanceof Number) config.maxAttempts = ((Number) ma).intValue();
        config.minBackoff = (String) row.get("min_backoff");
        config.maxBackoff = (String) row.get("max_backoff");
        Object md = row.get("max_doublings");
        if (md instanceof Number) config.maxDoublings = ((Number) md).intValue();
        config.maxRetryDuration = (String) row.get("max_retry_duration");
        config.httpTargetUri = (String) row.get("http_target_uri");
        config.httpTargetMethod = (String) row.get("http_target_method");
        return config;
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
