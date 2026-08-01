package com.localcloud.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Service-facing execution boundary. It owns lifecycle events; providers own execution. */
public final class RuntimeBroker implements AutoCloseable {
    private final RuntimeProvider provider;
    private final Map<String, WorkloadSpec> specs = new ConcurrentHashMap<>();
    private final Map<String, WorkloadResult> results = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<WorkloadSpec, WorkloadResult>> listeners = new CopyOnWriteArrayList<>();

    public RuntimeBroker(RuntimeProvider provider) {
        this.provider = provider;
    }

    public CompletableFuture<WorkloadResult> submit(WorkloadSpec spec) {
        if (specs.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalArgumentException("workload already exists: " + spec.id());
        }
        return provider.submit(spec, event -> record(spec, event))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        record(spec, new WorkloadResult(spec.id(), WorkloadResult.State.INFRA_ERROR, "",
                                spec.profile().image().digest(), null, WorkloadResult.ErrorCategory.AGENT,
                                failure.getMessage(), null, java.time.Instant.now(), java.util.List.of(),
                                java.util.Map.of(), false));
                    } else if (result != null) {
                        record(spec, result);
                    }
                });
    }

    public boolean cancel(String workloadId) {
        return provider.cancel(workloadId);
    }

    public Optional<WorkloadResult> inspect(String workloadId) {
        WorkloadResult result = results.get(workloadId);
        return result == null ? provider.inspect(workloadId) : Optional.of(result);
    }

    public void addListener(BiConsumer<WorkloadSpec, WorkloadResult> listener) {
        listeners.add(listener);
    }

    public RuntimeProvider provider() { return provider; }

    private void record(WorkloadSpec spec, WorkloadResult event) {
        results.compute(event.workloadId(), (id, current) -> newest(current, event));
        listeners.forEach(listener -> listener.accept(spec, event));
    }

    private static WorkloadResult newest(WorkloadResult current, WorkloadResult candidate) {
        if (current == null || !current.terminal()) return candidate;
        return current;
    }

    @Override public void close() { provider.close(); }
}
