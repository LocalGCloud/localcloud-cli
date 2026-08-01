package com.localcloud.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkloadResult(
        String workloadId,
        State state,
        String runtimeId,
        String imageDigest,
        Integer exitCode,
        ErrorCategory errorCategory,
        String message,
        Instant startedAt,
        Instant finishedAt,
        List<String> logs,
        Map<String, Double> metrics,
        boolean cleanupComplete) {

    public enum State { QUEUED, STARTING, RUNNING, SUCCEEDED, FAILED, CANCELLED, INFRA_ERROR }
    public enum ErrorCategory { NONE, POLICY, CAPABILITY, IMAGE, STAGING, EXECUTION, TIMEOUT, CANCELLED, AGENT, COMPARISON }

    public WorkloadResult {
        workloadId = Objects.requireNonNull(workloadId, "workloadId");
        state = Objects.requireNonNull(state, "state");
        runtimeId = Objects.requireNonNullElse(runtimeId, "");
        imageDigest = Objects.requireNonNullElse(imageDigest, "");
        errorCategory = Objects.requireNonNullElse(errorCategory, ErrorCategory.NONE);
        message = Objects.requireNonNullElse(message, "");
        logs = List.copyOf(Objects.requireNonNullElse(logs, List.of()));
        metrics = Map.copyOf(Objects.requireNonNullElse(metrics, Map.of()));
    }

    public boolean terminal() {
        return switch (state) {
            case SUCCEEDED, FAILED, CANCELLED, INFRA_ERROR -> true;
            default -> false;
        };
    }
}
