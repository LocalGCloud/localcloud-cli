package com.localcloud.migration;

import com.localcloud.runtime.WorkloadResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MigrationReport(
        String runId,
        String suiteRevision,
        State state,
        Verdict verdict,
        String startedAt,
        String finishedAt,
        List<CaseResult> cases,
        List<Finding> findings,
        String message,
        boolean cleanupComplete) {

    public enum State { QUEUED, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED }
    public enum Verdict { PASS, PASS_WITH_WARNINGS, FAIL, INFRA_ERROR, COMPARISON_ERROR, CANCELLED }
    public enum Dimension { CORRECTNESS, COMPATIBILITY, PERFORMANCE, INFRASTRUCTURE, COMPARISON, CLEANUP }

    public record Finding(Dimension dimension, Severity severity, String code, String message, String caseId) {}
    public enum Severity { INFO, WARNING, ERROR }

    public record CaseResult(
            String caseId,
            String profileRevision,
            String imageDigest,
            Verdict verdict,
            WorkloadResult workload,
            Map<String, String> outputs,
            Map<String, Double> metrics,
            List<Finding> findings) {
        public CaseResult {
            outputs = Map.copyOf(Objects.requireNonNullElse(outputs, Map.of()));
            metrics = Map.copyOf(Objects.requireNonNullElse(metrics, Map.of()));
            findings = List.copyOf(Objects.requireNonNullElse(findings, List.of()));
        }
    }

    public MigrationReport {
        cases = List.copyOf(Objects.requireNonNullElse(cases, List.of()));
        findings = List.copyOf(Objects.requireNonNullElse(findings, List.of()));
        message = Objects.requireNonNullElse(message, "");
    }
}
