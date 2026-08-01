package com.localcloud.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localcloud.integration.TestDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeProviderTest {
    @TempDir Path output;

    @Test
    void pollsPersistsCompletesAndCancelsWithoutDuplicateExecution() {
        try (TestDataSource dataSource = TestDataSource.create("runtime_agent_" + System.nanoTime())) {
            RuntimeWorkloadRepository repository = new RuntimeWorkloadRepository(dataSource.getDataSource());
            AgentRuntimeProvider provider = new AgentRuntimeProvider(repository);
            provider.register(new RuntimeAgentProtocol.Registration("agent", Set.of("spark"), List.of()));
            assertTrue(provider.available());

            WorkloadSpec first = spec("first");
            var completion = provider.submit(first, ignored -> {});
            RuntimeAgentProtocol.WorkItem claimed = provider.poll(new RuntimeAgentProtocol.Poll("agent", Set.of("spark")));
            assertEquals(first.id(), claimed.id());
            assertEquals(first.outputDirectory(), claimed.toSpec().outputDirectory());
            provider.acceptEvent(result(first, WorkloadResult.State.RUNNING));
            provider.acceptEvent(result(first, WorkloadResult.State.SUCCEEDED));
            provider.acceptEvent(result(first, WorkloadResult.State.RUNNING));
            assertEquals(WorkloadResult.State.SUCCEEDED, completion.join().state());
            assertEquals(WorkloadResult.State.SUCCEEDED, provider.inspect(first.id()).orElseThrow().state());

            WorkloadSpec second = spec("second");
            var cancelled = provider.submit(second, ignored -> {});
            assertTrue(provider.cancel(second.id()));
            assertEquals(WorkloadResult.State.CANCELLED, cancelled.join().state());
            assertFalse(provider.cancel(second.id()));
            assertTrue(repository.unfinished().isEmpty());
        }
    }

    private WorkloadSpec spec(String id) {
        RuntimeProfile profile = new RuntimeProfile("runtime", "dataproc", "2.0", 1, RuntimeProfile.Status.PUBLISHED,
                new RuntimeProfile.Image("localcloud/runtime", "sha256:" + "d".repeat(64), List.of("localcloud"), "verified"),
                null, Map.of("spark", "3.1.2"), Set.of("spark"), Map.of(), Map.of(), List.of());
        return new WorkloadSpec(id, "p", "resource", "run", profile, "spark", List.of("spark", "job.jar"),
                Map.of(), List.of(), output.resolve(id), Duration.ofSeconds(30), null);
    }

    private static WorkloadResult result(WorkloadSpec spec, WorkloadResult.State state) {
        boolean terminal = state == WorkloadResult.State.SUCCEEDED;
        return new WorkloadResult(spec.id(), state, "container", spec.profile().image().digest(), terminal ? 0 : null,
                WorkloadResult.ErrorCategory.NONE, "", Instant.now(), terminal ? Instant.now() : null,
                List.of(), Map.of("wallTimeSeconds", 1.0), terminal);
    }
}
