package com.localcloud.emulators.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.emulators.workflows.engine.CallbackManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * HTTP endpoint for delivering callbacks to waiting workflow executions.
 * <p>
 * Registered at {@code /workflows} and handles:
 * <pre>
 *   POST /workflows/callbacks/{callbackId}
 * </pre>
 * The request body can be any JSON value and is forwarded as the callback payload
 * to the workflow execution that is awaiting the callback.
 */
public class WorkflowsCallbackService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowsCallbackService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CallbackManager callbackManager;

    public WorkflowsCallbackService(CallbackManager callbackManager) {
        this.callbackManager = callbackManager;
    }

    /**
     * Deliver a callback payload to a waiting workflow execution.
     *
     * @param callbackId the callback ID issued by {@code events.create_callback_endpoint}
     * @param request    the HTTP request whose body is the callback payload (any JSON)
     * @return 200 with {@code {"status":"delivered"}} on success, 404 if not found/expired
     */
    @Post("/callbacks/{callbackId}")
    public HttpResponse deliverCallback(@Param("callbackId") String callbackId,
                                        AggregatedHttpRequest request) {
        try {
            // Parse request body as generic JSON payload (null if body is empty)
            Object payload = null;
            String body = request.contentUtf8();
            if (body != null && !body.isBlank()) {
                try {
                    payload = mapper.readValue(body, Object.class);
                } catch (Exception e) {
                    // Treat non-JSON body as raw string
                    payload = body;
                }
            }

            boolean delivered = callbackManager.deliverCallback(callbackId, payload);
            if (delivered) {
                logger.debug("Callback delivered: {}", callbackId);
                String json = mapper.writeValueAsString(Map.of("status", "delivered"));
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
            } else {
                logger.debug("Callback not found or already delivered: {}", callbackId);
                String json = mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Callback not found or already delivered: " + callbackId));
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON, json);
            }
        } catch (Exception e) {
            logger.error("Error delivering callback {}", callbackId, e);
            try {
                String json = mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", e.getMessage() != null ? e.getMessage() : "Internal error"));
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON, json);
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Internal error");
            }
        }
    }
}
