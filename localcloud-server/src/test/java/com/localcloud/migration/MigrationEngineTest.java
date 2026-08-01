package com.localcloud.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.localcloud.admin.ExportService;
import com.localcloud.admin.SeedService;
import com.localcloud.integration.TestDataSource;
import com.localcloud.runtime.RuntimeBroker;
import com.localcloud.runtime.RuntimeCatalogStore;
import com.localcloud.runtime.RuntimeProfile;
import com.localcloud.runtime.RuntimeProvider;
import com.localcloud.runtime.WorkloadResult;
import com.localcloud.runtime.WorkloadSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationEngineTest {
    @TempDir Path dataDir;

    @Test
    void runsEquivalentBaselineAndTargetAndPersistsPassingEvidence() throws Exception {
        try (TestDataSource dataSource = TestDataSource.create("migration_engine_" + System.nanoTime())) {
            RuntimeCatalogStore catalog = new RuntimeCatalogStore(dataDir);
            RuntimeProfile baseline = publish(catalog, "dataproc:1.2-debian9@1", 'a');
            RuntimeProfile target = publish(catalog, "dataproc:3.0-debian12@1", 'b');
            DeterministicProvider provider = new DeterministicProvider();
            RuntimeBroker broker = new RuntimeBroker(provider);
            MigrationRepository repository = new MigrationRepository(dataSource.getDataSource());
            ExportService exports = mock(ExportService.class);
            when(exports.exportYaml(java.util.Set.of())).thenReturn("services: {}\n");
            when(exports.captureMigrationState()).thenReturn(Map.of("gcs://fixture/input", "sha256:stable"));
            SeedService seeds = mock(SeedService.class);
            when(seeds.seedYaml(anyString(), anyBoolean())).thenReturn(Map.of("status", "seeded"));
            MigrationSuite suite = repository.saveSuite(new MigrationSuite("upgrade", 0, "Upgrade",
                    baseline.revisionId(), List.of(target.revisionId()), "spark-sql", List.of("-e", "SELECT 1"),
                    Map.of(), "bigquery:\n  datasets: []\n", List.of(), List.of("result.txt"), List.of(), 1.5, 30));

            try (MigrationEngine engine = new MigrationEngine("local-project", dataDir, catalog, broker,
                    repository, exports, seeds)) {
                MigrationReport queued = engine.start(suite);
                MigrationReport report = awaitTerminal(repository, queued.runId());
                assertEquals(MigrationReport.State.COMPLETED, report.state());
                assertEquals(MigrationReport.Verdict.PASS, report.verdict());
                assertEquals(2, report.cases().size());
                assertTrue(report.cleanupComplete());
                assertEquals("sha256:stable", report.cases().get(0).outputs().get("emulator:gcs://fixture/input"));
                verify(seeds, atLeast(3)).resetProjectData("local-project");
                verify(seeds, atLeast(2)).seedYaml(anyString(), anyBoolean());
            } finally {
                broker.close();
            }
        }
    }

    @Test
    void reconcilesInterruptedRunsWithoutReplayingAndRestoresSnapshotOnCleanup() throws Exception {
        try (TestDataSource dataSource = TestDataSource.create("migration_recovery_" + System.nanoTime())) {
            RuntimeCatalogStore catalog = new RuntimeCatalogStore(dataDir);
            RuntimeProfile baseline = publish(catalog, "dataproc:1.2-debian9@1", 'a');
            RuntimeProfile target = publish(catalog, "dataproc:3.0-debian12@1", 'b');
            MigrationRepository repository = new MigrationRepository(dataSource.getDataSource());
            MigrationSuite suite = repository.saveSuite(new MigrationSuite("recovery", 0, "Recovery",
                    baseline.revisionId(), List.of(target.revisionId()), "spark", List.of("job.jar"),
                    Map.of(), "", List.of(), List.of(), List.of(), 1.5, 30));
            repository.saveRun(new MigrationReport("restart-run", suite.revisionId(),
                    MigrationReport.State.RUNNING, MigrationReport.Verdict.INFRA_ERROR,
                    Instant.now().toString(), null, List.of(), List.of(), "Running", false));
            Path runRoot = dataDir.resolve("runtime-workspaces/migration-runs/restart-run");
            Files.createDirectories(runRoot);
            Files.writeString(runRoot.resolve("original-emulator-state.yaml"), "services: {}\n");

            DeterministicProvider provider = new DeterministicProvider();
            RuntimeBroker broker = new RuntimeBroker(provider);
            ExportService exports = mock(ExportService.class);
            SeedService seeds = mock(SeedService.class);
            when(seeds.seedYaml(anyString(), anyBoolean())).thenReturn(Map.of("status", "seeded"));

            try (MigrationEngine engine = new MigrationEngine("local-project", dataDir, catalog, broker,
                    repository, exports, seeds)) {
                MigrationReport recovered = repository.getRun("restart-run");
                assertEquals(MigrationReport.State.FAILED, recovered.state());
                assertEquals("CONTROL_PLANE_RESTART", recovered.findings().get(0).code());
                assertFalse(recovered.cleanupComplete());
                assertTrue(provider.cancelled.contains("migration-restart-run-baseline"));
                assertTrue(provider.cancelled.contains("migration-restart-run-target-1"));

                MigrationReport cleaned = engine.cleanup("restart-run");
                assertTrue(cleaned.cleanupComplete());
                assertFalse(Files.exists(runRoot));
                verify(seeds).resetProjectData("local-project");
                verify(seeds).seedYaml("services: {}\n", false);
            } finally {
                broker.close();
            }
        }
    }

    private static RuntimeProfile publish(RuntimeCatalogStore catalog, String revisionId, char digest) {
        return catalog.publish(revisionId, new RuntimeProfile.Image("localcloud/dataproc-runtime",
                "sha256:" + String.valueOf(digest).repeat(64), List.of("localcloud"), "verified"), "");
    }

    private static MigrationReport awaitTerminal(MigrationRepository repository, String runId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            MigrationReport report = repository.getRun(runId);
            if (report != null && (report.state() == MigrationReport.State.COMPLETED
                    || report.state() == MigrationReport.State.FAILED
                    || report.state() == MigrationReport.State.CANCELLED)) return report;
            Thread.sleep(10);
        }
        throw new AssertionError("migration run did not finish");
    }

    private static final class DeterministicProvider implements RuntimeProvider {
        private final AtomicInteger runs = new AtomicInteger();
        private final Map<String, WorkloadResult> results = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<String> cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override public CompletableFuture<WorkloadResult> submit(WorkloadSpec spec, Consumer<WorkloadResult> events) {
            try {
                Files.createDirectories(spec.outputDirectory());
                Files.writeString(spec.outputDirectory().resolve("result.txt"), "same-result");
                double seconds = runs.incrementAndGet() == 1 ? 1.0 : 1.1;
                WorkloadResult result = new WorkloadResult(spec.id(), WorkloadResult.State.SUCCEEDED, "local",
                        spec.profile().image().digest(), 0, WorkloadResult.ErrorCategory.NONE, "Completed",
                        Instant.now(), Instant.now(), List.of("completed"), Map.of("wallTimeSeconds", seconds), true);
                results.put(spec.id(), result);
                events.accept(result);
                return CompletableFuture.completedFuture(result);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        @Override public boolean cancel(String workloadId) { cancelled.add(workloadId); return true; }
        @Override public Optional<WorkloadResult> inspect(String workloadId) { return Optional.ofNullable(results.get(workloadId)); }
        @Override public boolean available() { return true; }
        @Override public String mode() { return "test"; }
        @Override public void close() {}
    }
}
