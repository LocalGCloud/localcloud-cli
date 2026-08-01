package com.localcloud.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeBrokerTest {
    @TempDir Path output;

    @Test
    void preservesTerminalResultWhenLateNonTerminalEventArrives() {
        RuntimeProfile profile = profile();
        RuntimeProvider provider = new RuntimeProvider() {
            private WorkloadResult result;
            @Override public CompletableFuture<WorkloadResult> submit(WorkloadSpec spec, Consumer<WorkloadResult> events) {
                events.accept(result(spec, WorkloadResult.State.RUNNING, null));
                result = result(spec, WorkloadResult.State.SUCCEEDED, 0);
                events.accept(result);
                events.accept(result(spec, WorkloadResult.State.RUNNING, null));
                return CompletableFuture.completedFuture(result);
            }
            @Override public boolean cancel(String id) { return true; }
            @Override public Optional<WorkloadResult> inspect(String id) { return Optional.ofNullable(result); }
            @Override public boolean available() { return true; }
            @Override public String mode() { return "test"; }
            @Override public void close() {}
        };
        RuntimeBroker broker = new RuntimeBroker(provider);
        WorkloadSpec spec = new WorkloadSpec("w", "p", "resource", "run", profile, "spark",
                List.of("spark", "job.jar"), Map.of(), List.of(), output, Duration.ofSeconds(5), null);

        assertEquals(WorkloadResult.State.SUCCEEDED, broker.submit(spec).join().state());
        assertEquals(WorkloadResult.State.SUCCEEDED, broker.inspect("w").orElseThrow().state());
        assertThrows(IllegalArgumentException.class, () -> broker.submit(spec));
    }

    private static RuntimeProfile profile() {
        return new RuntimeProfile("test", "spark", "3.5.0", 1, RuntimeProfile.Status.PUBLISHED,
                new RuntimeProfile.Image("localcloud/runtime", "sha256:" + "c".repeat(64), List.of("localcloud"), "test-signature"),
                null, Map.of("spark", "3.5.0"), Set.of("spark"), Map.of(), Map.of(), List.of());
    }

    private static WorkloadResult result(WorkloadSpec spec, WorkloadResult.State state, Integer exitCode) {
        return new WorkloadResult(spec.id(), state, spec.profile().revisionId(), spec.profile().image().digest(), exitCode,
                WorkloadResult.ErrorCategory.NONE, "", Instant.now(), state == WorkloadResult.State.RUNNING ? null : Instant.now(),
                List.of(), Map.of(), state == WorkloadResult.State.SUCCEEDED);
    }
}
