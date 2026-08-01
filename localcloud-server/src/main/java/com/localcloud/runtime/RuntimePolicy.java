package com.localcloud.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Central preflight guardrails for executable workloads. */
public final class RuntimePolicy {
    private final List<Path> workspaceRoots;

    public RuntimePolicy(List<Path> workspaceRoots) {
        this.workspaceRoots = workspaceRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    public void validate(WorkloadSpec spec) {
        RuntimeProfile.Image image = spec.profile().image();
        if (!image.immutableReference().contains("@sha256:")) {
            throw new PolicyException("runtime image is not pinned by digest");
        }
        Path output = canonical(spec.outputDirectory());
        if (!withinWorkspace(output)) {
            throw new PolicyException("output directory is outside approved workspace roots: " + output);
        }
        for (WorkloadSpec.Mount mount : spec.mounts()) {
            Path source = canonical(mount.source());
            boolean workloadOutput = source.equals(output);
            if (!withinWorkspace(source)) {
                throw new PolicyException("mount is outside approved workspace roots: " + source);
            }
            if (!mount.readOnly() && !workloadOutput) {
                throw new PolicyException("writable host mounts are limited to the workload output directory");
            }
            if (!mount.target().startsWith("/") || mount.target().contains("..")) {
                throw new PolicyException("invalid container mount target: " + mount.target());
            }
        }
    }

    private boolean withinWorkspace(Path path) {
        return workspaceRoots.stream().map(RuntimePolicy::canonical).anyMatch(path::startsWith);
    }

    private static Path canonical(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            Path parent = normalized.getParent();
            if (parent == null) return normalized;
            try {
                return parent.toRealPath().resolve(normalized.getFileName()).normalize();
            } catch (IOException ignored) {
                return normalized;
            }
        }
    }

    public static final class PolicyException extends IllegalArgumentException {
        public PolicyException(String message) { super(message); }
    }
}
