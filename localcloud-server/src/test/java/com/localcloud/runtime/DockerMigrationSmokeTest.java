package com.localcloud.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localcloud.docker.ContainerManager;
import com.localcloud.docker.DockerClientProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerMigrationSmokeTest {
    @TempDir Path workspace;

    @Test
    void executesSparkSqlInDigestPinnedCuratedRuntime() throws Exception {
        String reference = System.getenv("LOCALCLOUD_DATAPROC_SMOKE_IMAGE");
        String digest = System.getenv("LOCALCLOUD_DATAPROC_SMOKE_DIGEST");
        Assumptions.assumeTrue(reference != null && !reference.isBlank()
                && digest != null && digest.matches("sha256:[a-fA-F0-9]{64}"),
                "set LOCALCLOUD_DATAPROC_SMOKE_IMAGE and LOCALCLOUD_DATAPROC_SMOKE_DIGEST");
        try {
            DockerClientProvider.getClient().pingCmd().exec();
        } catch (Exception unavailable) {
            Assumptions.abort("Docker daemon is unavailable: " + unavailable.getMessage());
        }

        Path output = Files.createDirectories(workspace.resolve("output"));
        RuntimeProfile profile = new RuntimeProfile("smoke", "dataproc", "2.x", 1,
                RuntimeProfile.Status.PUBLISHED,
                new RuntimeProfile.Image(reference, digest, List.of(registry(reference)), "smoke-verified"),
                null, Map.of("spark", "3.x"), Set.of("spark-sql"), Map.of(), Map.of(), List.of());
        WorkloadSpec spec = new WorkloadSpec("migration-smoke-" + System.nanoTime(), "local-project",
                "migrationSuites/smoke@1", "smoke", profile, "spark-sql",
                List.of("spark-sql", "-e", "SELECT 1"), Map.of(),
                List.of(new WorkloadSpec.Mount(output, "/localcloud/output", false)), output,
                Duration.ofMinutes(5), WorkloadSpec.ResourceLimits.defaults());

        try (DockerRuntimeProvider provider = new DockerRuntimeProvider(
                new ContainerManager(DockerClientProvider.getClient()), new RuntimePolicy(List.of(workspace)))) {
            WorkloadResult result = provider.submit(spec, ignored -> {}).join();
            assertEquals(WorkloadResult.State.SUCCEEDED, result.state(), result.message());
            assertTrue(result.cleanupComplete());
        }
    }

    private static String registry(String reference) {
        int slash = reference.indexOf('/');
        return slash < 0 ? reference : reference.substring(0, slash);
    }
}
