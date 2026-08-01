package com.localcloud.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure, deterministic correctness and directional-performance comparison rules. */
final class MigrationComparator {
    static List<MigrationReport.Finding> assertions(List<MigrationSuite.Assertion> assertions,
                                                     Map<String, String> outputs,
                                                     List<String> logs, String caseId) {
        List<MigrationReport.Finding> findings = new ArrayList<>();
        String logText = String.join("", logs);
        for (MigrationSuite.Assertion assertion : assertions) {
            boolean passed = switch (assertion.type()) {
                case OUTPUT_EXISTS -> outputs.containsKey(assertion.target());
                case OUTPUT_SHA256 -> assertion.expected().equals(outputs.get(assertion.target()));
                case EMULATOR_SHA256 -> assertion.expected().equals(outputs.get("emulator:" + assertion.target()));
                case LOG_CONTAINS -> logText.contains(assertion.target());
                case LOG_NOT_CONTAINS -> !logText.contains(assertion.target());
            };
            if (!passed) {
                findings.add(new MigrationReport.Finding(MigrationReport.Dimension.CORRECTNESS,
                        MigrationReport.Severity.ERROR, "ASSERTION_" + assertion.type().name(),
                        "Assertion failed for " + assertion.target(), caseId));
            }
        }
        return List.copyOf(findings);
    }

    static List<MigrationReport.Finding> cases(MigrationReport.CaseResult baseline,
                                                MigrationReport.CaseResult target, double tolerance) {
        List<MigrationReport.Finding> findings = new ArrayList<>();
        if (!baseline.outputs().equals(target.outputs())) {
            findings.add(new MigrationReport.Finding(MigrationReport.Dimension.CORRECTNESS,
                    MigrationReport.Severity.ERROR, "OUTPUT_MISMATCH",
                    "Target output manifest differs from baseline", target.caseId()));
        }
        double baselineSeconds = baseline.metrics().getOrDefault("wallTimeSeconds", 0.0);
        double targetSeconds = target.metrics().getOrDefault("wallTimeSeconds", 0.0);
        if (baselineSeconds > 0 && targetSeconds / baselineSeconds > tolerance) {
            findings.add(new MigrationReport.Finding(MigrationReport.Dimension.PERFORMANCE,
                    MigrationReport.Severity.ERROR, "LOCAL_RUNTIME_REGRESSION",
                    "Target local wall time is " + String.format("%.2fx", targetSeconds / baselineSeconds)
                            + " baseline; local metrics are directional, not Dataproc capacity evidence", target.caseId()));
        }
        return List.copyOf(findings);
    }

    private MigrationComparator() {}
}
