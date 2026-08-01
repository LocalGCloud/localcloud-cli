package com.localcloud.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON-safe contracts exchanged over the authenticated polling agent API. */
public final class RuntimeAgentProtocol {
    public record Registration(String agentId, Set<String> capabilities, List<String> activeWorkloadIds) {}
    public record Poll(String agentId, Set<String> capabilities) {}
    public record RegistrationResult(List<String> cancelWorkloadIds) {}
    public record Commands(List<String> cancelWorkloadIds) {}
    public record AgentMount(String source, String target, boolean readOnly) {}
    public record WorkItem(
            String id, String projectId, String resourceName, String runId, RuntimeProfile profile,
            String capability, List<String> command, Map<String, String> environment,
            List<AgentMount> mounts, String outputDirectory, long timeoutMillis,
            WorkloadSpec.ResourceLimits limits) {
        public static WorkItem from(WorkloadSpec spec) {
            return new WorkItem(spec.id(), spec.projectId(), spec.resourceName(), spec.runId(), spec.profile(),
                    spec.capability(), spec.command(), spec.environment(), spec.mounts().stream()
                    .map(mount -> new AgentMount(mount.source().toString(), mount.target(), mount.readOnly())).toList(),
                    spec.outputDirectory().toString(), spec.timeout().toMillis(), spec.limits());
        }
        public WorkloadSpec toSpec() {
            return new WorkloadSpec(id, projectId, resourceName, runId, profile, capability, command, environment,
                    mounts.stream().map(mount -> new WorkloadSpec.Mount(Path.of(mount.source()), mount.target(), mount.readOnly())).toList(),
                    Path.of(outputDirectory), Duration.ofMillis(timeoutMillis), limits);
        }
    }
    private RuntimeAgentProtocol() {}
}
