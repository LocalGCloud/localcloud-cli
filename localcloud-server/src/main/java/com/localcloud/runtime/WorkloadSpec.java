package com.localcloud.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runtime-neutral, immutable executable workload request. */
public record WorkloadSpec(
        String id,
        String projectId,
        String resourceName,
        String runId,
        RuntimeProfile profile,
        String capability,
        List<String> command,
        Map<String, String> environment,
        List<Mount> mounts,
        Path outputDirectory,
        Duration timeout,
        ResourceLimits limits) {

    public record Mount(Path source, String target, boolean readOnly) {}
    public record ResourceLimits(long memoryBytes, long nanoCpus, int pids) {
        public static ResourceLimits defaults() { return new ResourceLimits(4L * 1024 * 1024 * 1024, 2_000_000_000L, 512); }
    }

    public WorkloadSpec {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        projectId = requireText(projectId, "projectId");
        resourceName = requireText(resourceName, "resourceName");
        runId = requireText(runId, "runId");
        profile = Objects.requireNonNull(profile, "profile");
        capability = requireText(capability, "capability");
        if (!profile.supports(capability)) throw new IllegalArgumentException(profile.revisionId() + " does not support " + capability);
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty()) throw new IllegalArgumentException("command is required");
        environment = Map.copyOf(Objects.requireNonNullElse(environment, Map.of()));
        mounts = List.copyOf(Objects.requireNonNullElse(mounts, List.of()));
        outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        timeout = Objects.requireNonNullElse(timeout, Duration.ofHours(1));
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        limits = Objects.requireNonNullElse(limits, ResourceLimits.defaults());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
