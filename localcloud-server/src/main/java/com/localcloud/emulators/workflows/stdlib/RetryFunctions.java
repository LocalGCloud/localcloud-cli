package com.localcloud.emulators.workflows.stdlib;

import java.util.Map;

public class RetryFunctions {
    public static void register(StdlibRegistry registry) {
        registry.register("retry.always", args -> true);
        registry.register("retry.never", args -> false);
        registry.register("retry.default_backoff", args -> Map.of(
                "initial_delay", 1.0,
                "max_delay", 60.0,
                "multiplier", 2.0
        ));
        registry.register("retry.default_retry", args -> {
            if (args.isEmpty()) return true;
            Object error = args.get(0);
            if (error instanceof Map<?, ?> map) {
                Object code = map.get("code");
                if (code instanceof Number n) {
                    int value = n.intValue();
                    return value == 429 || value == 502 || value == 503 || value == 504 || value >= 500;
                }
                if (code != null) {
                    String text = String.valueOf(code);
                    return "429".equals(text) || "502".equals(text) || "503".equals(text) ||
                            "504".equals(text) || text.startsWith("5");
                }
            }
            return false;
        });
    }
}
