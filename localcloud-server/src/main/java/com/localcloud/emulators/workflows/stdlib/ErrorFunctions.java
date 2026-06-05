package com.localcloud.emulators.workflows.stdlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements errors.create and errors.tag for Cloud Workflows error handling.
 */
public class ErrorFunctions {

    @SuppressWarnings("unchecked")
    public static void register(StdlibRegistry registry) {
        registry.register("errors.create", args -> {
            if (args.isEmpty()) throw new RuntimeException("errors.create requires (message)");
            String message = String.valueOf(args.get(0));
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "CustomError");
            error.put("message", message);
            error.put("tags", new ArrayList<String>());
            return error;
        });

        registry.register("errors.tag", args -> {
            if (args.size() < 2) throw new RuntimeException("errors.tag requires (error, tag)");
            Map<String, Object> error = new LinkedHashMap<>((Map<String, Object>) args.get(0));
            String tag = String.valueOf(args.get(1));
            List<String> tags = error.containsKey("tags") && error.get("tags") instanceof List<?>
                    ? new ArrayList<>((List<String>) error.get("tags"))
                    : new ArrayList<>();
            if (!tags.contains(tag)) tags.add(tag);
            error.put("tags", tags);
            return error;
        });
    }
}
