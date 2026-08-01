package com.localcloud.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Explicit non-executing provider used when no trusted runtime is available. */
public final class MetadataOnlyRuntimeProvider implements RuntimeProvider {
    @Override
    public CompletableFuture<WorkloadResult> submit(WorkloadSpec spec, Consumer<WorkloadResult> events) {
        WorkloadResult result = new WorkloadResult(spec.id(), WorkloadResult.State.INFRA_ERROR, "",
                spec.profile().image().digest(), null, WorkloadResult.ErrorCategory.AGENT,
                "Runtime execution is unavailable; connect a Docker runtime agent", null, Instant.now(),
                List.of(), Map.of(), true);
        events.accept(result);
        return CompletableFuture.completedFuture(result);
    }

    @Override public boolean cancel(String workloadId) { return false; }
    @Override public Optional<WorkloadResult> inspect(String workloadId) { return Optional.empty(); }
    @Override public boolean available() { return false; }
    @Override public String mode() { return "metadata-only"; }
}
