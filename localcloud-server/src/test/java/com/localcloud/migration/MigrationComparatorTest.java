package com.localcloud.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localcloud.runtime.WorkloadResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationComparatorTest {
    @Test
    void evaluatesDeclaredFileEmulatorAndLogAssertions() {
        List<MigrationSuite.Assertion> assertions = List.of(
                new MigrationSuite.Assertion(MigrationSuite.AssertionType.OUTPUT_EXISTS, "result.json", ""),
                new MigrationSuite.Assertion(MigrationSuite.AssertionType.OUTPUT_SHA256, "result.json", "sha256:ok"),
                new MigrationSuite.Assertion(MigrationSuite.AssertionType.EMULATOR_SHA256, "gcs://bucket/object", "sha256:data"),
                new MigrationSuite.Assertion(MigrationSuite.AssertionType.LOG_NOT_CONTAINS, "Exception", ""));
        Map<String, String> outputs = Map.of("result.json", "sha256:ok",
                "emulator:gcs://bucket/object", "sha256:data");
        assertTrue(MigrationComparator.assertions(assertions, outputs, List.of("completed"), "target").isEmpty());
        assertEquals("ASSERTION_LOG_NOT_CONTAINS", MigrationComparator.assertions(
                assertions, outputs, List.of("Exception"), "target").get(0).code());
    }

    @Test
    void separatesOutputMismatchFromDirectionalPerformanceRegression() {
        MigrationReport.CaseResult baseline = caseResult("baseline", Map.of("a", "sha256:1"), 2.0);
        MigrationReport.CaseResult target = caseResult("target", Map.of("a", "sha256:2"), 4.0);
        List<MigrationReport.Finding> findings = MigrationComparator.cases(baseline, target, 1.5);
        assertEquals(List.of("OUTPUT_MISMATCH", "LOCAL_RUNTIME_REGRESSION"), findings.stream().map(MigrationReport.Finding::code).toList());
        assertEquals(MigrationReport.Dimension.CORRECTNESS, findings.get(0).dimension());
        assertEquals(MigrationReport.Dimension.PERFORMANCE, findings.get(1).dimension());
    }

    private static MigrationReport.CaseResult caseResult(String id, Map<String, String> outputs, double seconds) {
        WorkloadResult workload = new WorkloadResult(id, WorkloadResult.State.SUCCEEDED, "runtime", "sha256:" + "e".repeat(64),
                0, WorkloadResult.ErrorCategory.NONE, "", Instant.now(), Instant.now(), List.of(),
                Map.of("wallTimeSeconds", seconds), true);
        return new MigrationReport.CaseResult(id, "profile@1", workload.imageDigest(), MigrationReport.Verdict.PASS,
                workload, outputs, workload.metrics(), List.of());
    }
}
