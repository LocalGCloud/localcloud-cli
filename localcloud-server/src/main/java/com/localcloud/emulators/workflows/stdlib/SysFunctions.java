package com.localcloud.emulators.workflows.stdlib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class SysFunctions {
    private static final Logger logger = LoggerFactory.getLogger(SysFunctions.class);

    public static void register(StdlibRegistry registry) {
        registry.register("sys.get_env", args -> {
            if (args.isEmpty()) throw new RuntimeException("sys.get_env requires a variable name");
            String name = String.valueOf(args.get(0));
            String val = System.getenv(name);
            return val; // returns null if not set
        });

        registry.register("sys.log", args -> {
            if (args.isEmpty()) throw new RuntimeException("sys.log requires a message");
            String message = String.valueOf(args.get(0));
            String severity = args.size() > 1 ? String.valueOf(args.get(1)).toUpperCase() : "INFO";
            switch (severity) {
                case "WARNING" -> logger.warn("[workflow] {}", message);
                case "ERROR" -> logger.error("[workflow] {}", message);
                default -> logger.info("[workflow] {}", message);
            }
            return null;
        });

        registry.register("sys.now", args -> Instant.now().toString());

        registry.register("sys.sleep", args -> {
            if (args.isEmpty()) throw new RuntimeException("sys.sleep requires seconds");
            double seconds = ((Number) args.get(0)).doubleValue();
            long millis = (long) (Math.min(seconds, 60) * 1000); // Cap at 60s in emulator
            try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        });
    }
}
