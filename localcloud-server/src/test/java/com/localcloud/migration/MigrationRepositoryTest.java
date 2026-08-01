package com.localcloud.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.localcloud.integration.TestDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationRepositoryTest {
    @Test
    void versionsSuitesAndPersistsRunEvidence() {
        try (TestDataSource dataSource = TestDataSource.create("migration_repo_" + System.nanoTime())) {
            MigrationRepository repository = new MigrationRepository(dataSource.getDataSource());
            MigrationSuite request = new MigrationSuite("suite-a", 0, "Upgrade", "dataproc:1.2",
                    List.of("dataproc:2.0"), "spark", List.of("job.jar"), Map.of(), "gcs: {}",
                    List.of(), List.of("result.json"), List.of(), 1.5, 300);

            MigrationSuite first = repository.saveSuite(request);
            MigrationSuite second = repository.saveSuite(request);
            assertEquals(1, first.revision());
            assertEquals(2, second.revision());
            assertEquals(second, repository.getSuite("suite-a", null));
            assertEquals(1, repository.listSuites().size());

            MigrationReport report = new MigrationReport("run-a", second.revisionId(),
                    MigrationReport.State.COMPLETED, MigrationReport.Verdict.PASS,
                    Instant.now().toString(), Instant.now().toString(), List.of(), List.of(), "Completed", true);
            repository.saveRun(report);
            assertEquals(report, repository.getRun("run-a"));
            assertEquals(List.of(report), repository.listRuns());
        }
    }
}
