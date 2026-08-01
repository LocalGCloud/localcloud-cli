package com.localcloud.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

/** Control-plane provider for authenticated, outbound-polling host agents. */
public final class AgentRuntimeProvider implements RuntimeProvider {
    private static final Duration AGENT_TTL = Duration.ofSeconds(30);

    private final RuntimeWorkloadRepository repository;
    private final LinkedBlockingDeque<WorkloadSpec> pending = new LinkedBlockingDeque<>();
    private final Map<String, WorkloadSpec> specs = new ConcurrentHashMap<>();
    private final Map<String, WorkloadResult> results = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<WorkloadResult>> completions = new ConcurrentHashMap<>();
    private final Map<String, Consumer<WorkloadResult>> eventConsumers = new ConcurrentHashMap<>();
    private final Map<String, Instant> agents = new ConcurrentHashMap<>();
    private final List<RuntimeWorkloadRepository.Record> recovering;
    private volatile boolean recoveryResolved;

    public AgentRuntimeProvider(RuntimeWorkloadRepository repository) {
        this.repository = repository;
        this.recovering = repository.unfinished();
        recovering.forEach(record -> {
            specs.put(record.spec().id(), record.spec());
            results.put(record.spec().id(), record.result());
        });
    }

    @Override public CompletableFuture<WorkloadResult> submit(WorkloadSpec spec, Consumer<WorkloadResult> events) {
        if (specs.putIfAbsent(spec.id(), spec) != null) throw new IllegalArgumentException("workload already exists: " + spec.id());
        WorkloadResult queued = event(spec, WorkloadResult.State.QUEUED, WorkloadResult.ErrorCategory.NONE,
                "Queued for a runtime agent", false);
        results.put(spec.id(), queued);
        eventConsumers.put(spec.id(), events);
        repository.saveSpec(spec, queued);
        events.accept(queued);
        if (!available()) {
            WorkloadResult unavailable = event(spec, WorkloadResult.State.INFRA_ERROR, WorkloadResult.ErrorCategory.AGENT,
                    "No connected runtime agent supports workload execution", false);
            acceptEvent(unavailable);
            return CompletableFuture.completedFuture(unavailable);
        }
        CompletableFuture<WorkloadResult> completion = new CompletableFuture<>();
        completions.put(spec.id(), completion);
        pending.offer(spec);
        return completion;
    }

    public synchronized RuntimeAgentProtocol.RegistrationResult register(RuntimeAgentProtocol.Registration registration) {
        requireAgentId(registration.agentId());
        agents.put(registration.agentId(), Instant.now());
        List<String> active = List.copyOf(registration.activeWorkloadIds() == null ? List.of() : registration.activeWorkloadIds());
        List<String> cancelUnknown = active.stream().filter(id -> !specs.containsKey(id)).toList();
        if (!recoveryResolved) {
            for (RuntimeWorkloadRepository.Record record : recovering) {
                if (!active.contains(record.spec().id())) {
                    WorkloadResult interrupted = event(record.spec(), WorkloadResult.State.INFRA_ERROR,
                            WorkloadResult.ErrorCategory.AGENT,
                            "Runtime agent reconnected without the previously owned container; workload was not duplicated", false);
                    results.put(interrupted.workloadId(), interrupted);
                    repository.saveResult(interrupted);
                }
            }
            recoveryResolved = true;
        }
        List<String> cancellations = new ArrayList<>(cancelUnknown);
        cancellations.addAll(repository.cancellations());
        return new RuntimeAgentProtocol.RegistrationResult(List.copyOf(cancellations));
    }

    public synchronized RuntimeAgentProtocol.WorkItem poll(RuntimeAgentProtocol.Poll request) {
        requireAgentId(request.agentId());
        agents.put(request.agentId(), Instant.now());
        Set<String> capabilities = request.capabilities() == null ? Set.of() : request.capabilities();
        for (WorkloadSpec spec : pending) {
            if (capabilities.isEmpty() || capabilities.contains(spec.capability())) {
                if (pending.remove(spec)) return RuntimeAgentProtocol.WorkItem.from(spec);
            }
        }
        return null;
    }

    public void acceptEvent(WorkloadResult candidate) {
        WorkloadSpec spec = specs.get(candidate.workloadId());
        if (spec == null) throw new IllegalArgumentException("unknown workload: " + candidate.workloadId());
        if (!candidate.imageDigest().isBlank() && !candidate.imageDigest().equals(spec.profile().image().digest())) {
            throw new IllegalArgumentException("runtime agent reported a different image digest");
        }
        WorkloadResult accepted = results.compute(candidate.workloadId(), (id, current) -> newest(current, candidate));
        repository.saveResult(accepted);
        Consumer<WorkloadResult> consumer = eventConsumers.get(candidate.workloadId());
        if (consumer != null) consumer.accept(accepted);
        if (accepted.terminal()) {
            CompletableFuture<WorkloadResult> completion = completions.remove(candidate.workloadId());
            if (completion != null) completion.complete(accepted);
            eventConsumers.remove(candidate.workloadId());
        }
    }

    public RuntimeAgentProtocol.Commands commands(String agentId) {
        requireAgentId(agentId);
        agents.put(agentId, Instant.now());
        return new RuntimeAgentProtocol.Commands(repository.cancellations());
    }

    @Override public boolean cancel(String workloadId) {
        WorkloadResult current = results.get(workloadId);
        if (current == null || current.terminal()) return false;
        repository.requestCancellation(workloadId);
        pending.removeIf(spec -> spec.id().equals(workloadId));
        if (current.state() == WorkloadResult.State.QUEUED) {
            WorkloadResult cancelled = event(specs.get(workloadId), WorkloadResult.State.CANCELLED,
                    WorkloadResult.ErrorCategory.CANCELLED, "Cancelled before agent claim", true);
            acceptEvent(cancelled);
        }
        return true;
    }

    @Override public Optional<WorkloadResult> inspect(String workloadId) { return Optional.ofNullable(results.get(workloadId)); }
    @Override public boolean available() {
        Instant cutoff = Instant.now().minus(AGENT_TTL);
        agents.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        return !agents.isEmpty();
    }
    @Override public String mode() { return "polling-agent"; }
    @Override public void close() {
        completions.values().forEach(future -> future.completeExceptionally(new IllegalStateException("runtime provider closed")));
    }

    private static void requireAgentId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("agentId is required");
    }

    private static WorkloadResult newest(WorkloadResult current, WorkloadResult candidate) {
        if (current == null || !current.terminal()) return candidate;
        return current;
    }

    private static WorkloadResult event(WorkloadSpec spec, WorkloadResult.State state,
                                        WorkloadResult.ErrorCategory category, String message, boolean cleanup) {
        Instant now = Instant.now();
        return new WorkloadResult(spec.id(), state, "", spec.profile().image().digest(), null, category,
                message, state == WorkloadResult.State.QUEUED ? null : now, terminal(state) ? now : null,
                List.of(), Map.of(), cleanup);
    }

    private static boolean terminal(WorkloadResult.State state) {
        return state == WorkloadResult.State.SUCCEEDED || state == WorkloadResult.State.FAILED
                || state == WorkloadResult.State.CANCELLED || state == WorkloadResult.State.INFRA_ERROR;
    }
}
