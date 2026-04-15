package com.localcloud.emulators.workflows.stdlib;

import com.localcloud.emulators.workflows.engine.CallbackManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements events.create_callback_endpoint and events.await_callback.
 */
public class EventsFunctions {

    public static void register(StdlibRegistry registry, CallbackManager callbackManager, String callbackBaseUrl) {
        registry.register("events.create_callback_endpoint", args -> {
            if (callbackManager == null) throw new RuntimeException("Callback manager not initialized");
            String callbackId = callbackManager.createCallback();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", callbackBaseUrl + "/" + callbackId);
            return result;
        });

        registry.register("events.await_callback", args -> {
            if (callbackManager == null) throw new RuntimeException("Callback manager not initialized");
            if (args.isEmpty()) throw new RuntimeException("events.await_callback requires a callback map");

            @SuppressWarnings("unchecked")
            Map<String, Object> callbackInfo = (Map<String, Object>) args.get(0);
            String url = (String) callbackInfo.get("url");
            if (url == null) throw new RuntimeException("Callback map must have 'url' field");

            String callbackId = url.substring(url.lastIndexOf('/') + 1);
            long timeoutSeconds = 0;
            if (args.size() > 1 && args.get(1) instanceof Number n) {
                timeoutSeconds = n.longValue();
            }
            if (callbackInfo.containsKey("timeout") && callbackInfo.get("timeout") instanceof Number n) {
                timeoutSeconds = n.longValue();
            }

            return callbackManager.awaitCallback(callbackId, timeoutSeconds);
        });
    }

    // Keep backward-compatible no-arg version for StdlibRegistry default constructor
    public static void register(StdlibRegistry registry) {
        // No-op: events functions are registered later with callbackManager
    }
}
