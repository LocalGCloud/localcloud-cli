package com.localcloud.admin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured compatibility error for explicitly unsupported local cloud paths.
 */
public record UnsupportedOperationError(
        int code,
        String status,
        String message,
        String reason,
        String service,
        String operation,
        String surface,
        String supportStatus,
        String workaround,
        String coverageUrl
) {
    public Map<String, Object> toResponseBody() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("status", status);
        error.put("message", message);
        error.put("reason", reason);
        error.put("service", service);
        error.put("operation", operation);
        error.put("surface", surface);
        error.put("support_status", supportStatus);
        error.put("workaround", workaround);
        error.put("coverage_url", coverageUrl);
        return Map.of("error", error);
    }
}
