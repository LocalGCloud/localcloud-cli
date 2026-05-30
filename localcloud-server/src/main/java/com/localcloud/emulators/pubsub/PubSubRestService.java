package com.localcloud.emulators.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * REST endpoints for Pub/Sub topic and subscription management.
 * Terraform's google_pubsub_topic and google_pubsub_subscription resources
 * use these REST paths (not the gRPC data plane on port 8085).
 *
 * Routes: /v1/projects/{project}/topics[/{topic}]
 *         /v1/projects/{project}/subscriptions[/{subscription}]
 */
public class PubSubRestService {

    private static final Logger logger = LoggerFactory.getLogger(PubSubRestService.class);

    private final PubSubStore store;
    private final PubSubEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();

    public PubSubRestService(PubSubStore store, PubSubEmulator emulator) {
        this.store = store;
        this.emulator = emulator;
    }

    public PubSubRestService(PubSubStore store) {
        this(store, null);
    }

    // ==================== Topics ====================

    @Put("/projects/{project}/topics/{topic}")
    public HttpResponse createTopic(@Param String project, @Param String topic, String body) {
        if (emulator != null) emulator.incrementRequestCount();
        try {
            Map<String, String> labels = null;
            if (body != null && !body.isBlank()) {
                var parsed = mapper.readTree(body);
                if (parsed.has("labels")) {
                    labels = mapper.convertValue(parsed.get("labels"), Map.class);
                }
            }
            boolean created = store.createTopic(project, topic, labels);
            if (!created) {
                return errorResponse(409, "Topic already exists: " + topic);
            }
            ObjectNode result = topicToJson(project, topic, labels);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error creating topic", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/topics/{topic}")
    public HttpResponse getTopic(@Param String project, @Param String topic) {
        if (emulator != null) emulator.incrementRequestCount();
        try {
            var data = store.getTopic(project, topic);
            if (data == null) {
                return errorResponse(404, "Topic not found: " + topic);
            }
            ObjectNode result = topicToJson(project, topic, null);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting topic", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/topics/{topic}")
    public HttpResponse deleteTopic(@Param String project, @Param String topic) {
        if (emulator != null) emulator.incrementRequestCount();
        try {
            store.deleteTopic(project, topic);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}");
        } catch (Exception e) {
            logger.error("Error deleting topic", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/topics")
    public HttpResponse listTopics(@Param String project) {
        if (emulator != null) emulator.incrementRequestCount();
        try {
            ObjectNode root = mapper.createObjectNode();
            var list = root.putArray("topics");
            for (var row : store.listTopics(project, 1000, 0)) {
                String topicId = String.valueOf(row.get("topic_id"));
                list.add(topicToJson(project, topicId, null));
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(root));
        } catch (Exception e) {
            logger.error("Error listing topics", e);
            return errorResponse(500, e.getMessage());
        }
    }

    // ==================== Subscriptions ====================

    @Put("/projects/{project}/subscriptions/{subscription}")
    public HttpResponse createSubscription(@Param String project, @Param String subscription, String body) {
        emulator.incrementRequestCount();
        try {
            var parsed = mapper.readTree(body);
            String topicFull = parsed.path("topic").asText();
            if (topicFull.isEmpty()) {
                return errorResponse(400, "Missing required field: topic");
            }
            if (emulator != null) emulator.incrementRequestCount();
            String[] topicParts = PubSubStore.parseTopicName(topicFull);

            if (!store.topicExists(topicParts[0], topicParts[1])) {
                return errorResponse(404, "Topic not found: " + topicFull);
            }

            int ackDeadlineSeconds = parsed.path("ackDeadlineSeconds").asInt(10);
            String pushEndpoint = parsed.path("pushConfig").path("pushEndpoint").asText(null);
            Map<String, String> labels = parsed.has("labels")
                    ? mapper.convertValue(parsed.get("labels"), Map.class) : null;

            int maxDeliveryAttempts = parsed.path("deadLetterPolicy").path("maxDeliveryAttempts").asInt(5);
            String deadLetterTopic = parsed.path("deadLetterPolicy").path("deadLetterTopic").asText(null);
            long minRetryBackoff = parsed.path("retryPolicy").path("minimumBackoff").asText("10s").replaceAll("[^0-9]", "").isEmpty()
                    ? 10 : Long.parseLong(parsed.path("retryPolicy").path("minimumBackoff").asText("10s").replaceAll("[^0-9]", ""));
            long maxRetryBackoff = parsed.path("retryPolicy").path("maximumBackoff").asText("600s").replaceAll("[^0-9]", "").isEmpty()
                    ? 600 : Long.parseLong(parsed.path("retryPolicy").path("maximumBackoff").asText("600s").replaceAll("[^0-9]", ""));

            store.createSubscription(project, subscription, topicParts[0], topicParts[1],
                    ackDeadlineSeconds, pushEndpoint, labels,
                    maxDeliveryAttempts, deadLetterTopic, minRetryBackoff, maxRetryBackoff);

            ObjectNode result = subToJson(project, subscription, topicFull, ackDeadlineSeconds, pushEndpoint);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (IllegalArgumentException e) {
            return errorResponse(400, e.getMessage());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                return errorResponse(409, "Subscription already exists: " + subscription);
            }
            logger.error("Error creating subscription", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/subscriptions/{subscription}")
    public HttpResponse getSubscription(@Param String project, @Param String subscription) {
        if (emulator != null) emulator.incrementRequestCount();
        try {
            var data = store.getSubscription(project, subscription);
            if (data == null) {
                return errorResponse(404, "Subscription not found: " + subscription);
            }
            String topicFull = PubSubStore.topicName(
                    (String) data.get("topic_project_id"), (String) data.get("topic_id"));
            int ackDeadline = data.get("ack_deadline_seconds") instanceof Number
                    ? ((Number) data.get("ack_deadline_seconds")).intValue() : 10;
            String pushEndpoint = (String) data.get("push_endpoint");
            ObjectNode result = subToJson(project, subscription, topicFull, ackDeadline, pushEndpoint);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting subscription", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/subscriptions/{subscription}")
    public HttpResponse deleteSubscription(@Param String project, @Param String subscription) {
        if (emulator != null) emulator.incrementRequestCount();
        try {
            store.deleteSubscription(project, subscription);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}");
        } catch (Exception e) {
            logger.error("Error deleting subscription", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/subscriptions")
    public HttpResponse listSubscriptions(@Param String project) {
        if (emulator != null) emulator.incrementRequestCount();
        try {
            ObjectNode root = mapper.createObjectNode();
            var list = root.putArray("subscriptions");
            for (var row : store.listSubscriptions(project, 1000, 0)) {
                String subId = String.valueOf(row.get("subscription_id"));
                String topicFull = PubSubStore.topicName(
                        (String) row.get("topic_project_id"), (String) row.get("topic_id"));
                int ackDeadline = row.get("ack_deadline_seconds") instanceof Number
                        ? ((Number) row.get("ack_deadline_seconds")).intValue() : 10;
                String pushEndpoint = (String) row.get("push_endpoint");
                list.add(subToJson(project, subId, topicFull, ackDeadline, pushEndpoint));
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(root));
        } catch (Exception e) {
            logger.error("Error listing subscriptions", e);
            return errorResponse(500, e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private ObjectNode topicToJson(String project, String topicId, Map<String, String> labels) {
        ObjectNode json = mapper.createObjectNode();
        json.put("name", PubSubStore.topicName(project, topicId));
        if (labels != null && !labels.isEmpty()) {
            json.set("labels", mapper.valueToTree(labels));
        }
        return json;
    }

    private ObjectNode subToJson(String project, String subId, String topicFull,
                                  int ackDeadlineSecs, String pushEndpoint) {
        ObjectNode json = mapper.createObjectNode();
        json.put("name", PubSubStore.subName(project, subId));
        json.put("topic", topicFull);
        json.put("ackDeadlineSeconds", ackDeadlineSecs);
        if (pushEndpoint != null && !pushEndpoint.isEmpty()) {
            ObjectNode pushConfig = json.putObject("pushConfig");
            pushConfig.put("pushEndpoint", pushEndpoint);
        }
        return json;
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
