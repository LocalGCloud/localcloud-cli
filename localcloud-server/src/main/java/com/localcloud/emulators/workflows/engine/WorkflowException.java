package com.localcloud.emulators.workflows.engine;

import java.util.List;
import java.util.Map;

/**
 * Exception thrown during workflow execution.
 * Can carry a structured error (code + message) matching GCP Workflows error format.
 */
public class WorkflowException extends RuntimeException {
    private final String code;
    private final Map<String, Object> tags;
    private List<Map<String, String>> workflowStackTrace; // [{step, routine}]

    public WorkflowException(String message) {
        super(message);
        this.code = "RuntimeError";
        this.tags = null;
    }

    public WorkflowException(String code, String message) {
        super(message);
        this.code = code;
        this.tags = null;
    }

    public WorkflowException(String code, String message, Map<String, Object> tags) {
        super(message);
        this.code = code;
        this.tags = tags;
    }

    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
        this.code = "RuntimeError";
        this.tags = null;
    }

    public String getCode() { return code; }
    public Map<String, Object> getTags() { return tags; }

    public void setWorkflowStackTrace(List<String> stackTrace) {
        // Convert flat string list to structured format for backward compat
        if (stackTrace != null) {
            this.workflowStackTrace = new java.util.ArrayList<>();
            for (String step : stackTrace) {
                Map<String, String> entry = new java.util.LinkedHashMap<>();
                entry.put("step", step);
                entry.put("routine", "main");
                this.workflowStackTrace.add(entry);
            }
        }
    }

    public void setStructuredStackTrace(List<Map<String, String>> stackTrace) {
        this.workflowStackTrace = stackTrace;
    }

    public List<Map<String, String>> getStructuredStackTrace() { return workflowStackTrace; }

    public Map<String, Object> toErrorMap() {
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("code", code);
        error.put("message", getMessage());
        if (tags != null) error.put("tags", tags);
        if (workflowStackTrace != null && !workflowStackTrace.isEmpty()) {
            error.put("stackTrace", workflowStackTrace);
        }
        return error;
    }
}
