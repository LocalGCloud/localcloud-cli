package com.localcloud.emulators.workflows.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages callback endpoints for workflow executions.
 * Supports create_callback_endpoint / await_callback pattern.
 */
public class CallbackManager {
    private static final Logger logger = LoggerFactory.getLogger(CallbackManager.class);
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    private final ConcurrentHashMap<String, CompletableFuture<Object>> pendingCallbacks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "callback-cleanup");
        t.setDaemon(true);
        return t;
    });

    /**
     * Create a new callback endpoint. Returns the callback ID.
     */
    public String createCallback() {
        String callbackId = UUID.randomUUID().toString();
        pendingCallbacks.put(callbackId, new CompletableFuture<>());

        // Schedule cleanup after timeout
        cleanupExecutor.schedule(() -> {
            CompletableFuture<Object> future = pendingCallbacks.remove(callbackId);
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new WorkflowException("TimeoutError", "Callback timed out"));
            }
        }, DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        logger.debug("Created callback endpoint: {}", callbackId);
        return callbackId;
    }

    /**
     * Wait for a callback to be received.
     * @param callbackId the callback ID
     * @param timeoutSeconds timeout in seconds (0 = use default)
     * @return the callback payload
     */
    public Object awaitCallback(String callbackId, long timeoutSeconds) {
        CompletableFuture<Object> future = pendingCallbacks.get(callbackId);
        if (future == null) {
            throw new WorkflowException("NotFound", "Callback not found: " + callbackId);
        }

        long timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_MS / 1000;
        try {
            Object result = future.get(timeout, TimeUnit.SECONDS);
            pendingCallbacks.remove(callbackId);
            return result;
        } catch (TimeoutException e) {
            pendingCallbacks.remove(callbackId);
            throw new WorkflowException("TimeoutError", "Callback timed out after " + timeout + "s");
        } catch (ExecutionException e) {
            pendingCallbacks.remove(callbackId);
            if (e.getCause() instanceof WorkflowException we) throw we;
            throw new WorkflowException("CallbackError", e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowException("Cancelled", "Callback wait interrupted");
        }
    }

    /**
     * Deliver a callback payload (called from the HTTP endpoint).
     * @return true if callback was found and delivered, false if expired/unknown
     */
    public boolean deliverCallback(String callbackId, Object payload) {
        CompletableFuture<Object> future = pendingCallbacks.get(callbackId);
        if (future == null || future.isDone()) {
            return false;
        }
        future.complete(payload);
        logger.debug("Delivered callback: {}", callbackId);
        return true;
    }

    /**
     * Check if a callback is still pending.
     */
    public boolean isPending(String callbackId) {
        CompletableFuture<Object> future = pendingCallbacks.get(callbackId);
        return future != null && !future.isDone();
    }

    public void shutdown() {
        cleanupExecutor.shutdownNow();
        pendingCallbacks.values().forEach(f -> f.cancel(true));
        pendingCallbacks.clear();
    }
}
