package com.localcloud.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimePolicyTest {
    @TempDir Path temporary;

    @Test
    void permitsReadOnlyWorkspaceInputsAndOnlyTheDeclaredWritableOutput() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path input = Files.writeString(workspace.resolve("job.py"), "print('ok')");
        Path output = Files.createDirectories(workspace.resolve("output"));
        RuntimePolicy policy = new RuntimePolicy(List.of(workspace));

        assertDoesNotThrow(() -> policy.validate(spec(output, List.of(
                new WorkloadSpec.Mount(input, "/workspace/job.py", true),
                new WorkloadSpec.Mount(output, "/localcloud/output", false)))));
        assertThrows(RuntimePolicy.PolicyException.class, () -> policy.validate(spec(output, List.of(
                new WorkloadSpec.Mount(input, "/workspace/job.py", false)))));
    }

    @Test
    void rejectsOutputsOutsideWorkspaceAndSymlinkEscapes() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        RuntimePolicy policy = new RuntimePolicy(List.of(workspace));

        assertThrows(RuntimePolicy.PolicyException.class, () -> policy.validate(spec(outside, List.of())));
        Path link = workspace.resolve("escape");
        Files.createSymbolicLink(link, outside);
        assertThrows(RuntimePolicy.PolicyException.class, () -> policy.validate(spec(link, List.of(
                new WorkloadSpec.Mount(link, "/localcloud/output", false)))));
    }

    private static WorkloadSpec spec(Path output, List<WorkloadSpec.Mount> mounts) {
        RuntimeProfile profile = new RuntimeProfile("runtime", "dataproc", "2.0", 1,
                RuntimeProfile.Status.PUBLISHED,
                new RuntimeProfile.Image("localcloud/runtime", "sha256:" + "a".repeat(64),
                        List.of("localcloud"), "verified"),
                null, Map.of(), Set.of("spark"), Map.of(), Map.of(), List.of());
        return new WorkloadSpec("workload", "project", "resource", "run", profile, "spark",
                List.of("spark", "job.jar"), Map.of(), mounts, output, Duration.ofSeconds(30), null);
    }
}
