package com.localcloud.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.localcloud.docker.ContainerManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Docker-backed runtime. All containers are unprivileged, labeled, bounded, and digest pinned. */
public final class DockerRuntimeProvider implements RuntimeProvider {
    private final DockerClient docker;
    private final RuntimePolicy policy;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String networkName = System.getenv().getOrDefault("LOCALCLOUD_RUNTIME_NETWORK", "localcloud-runtime");
    private final Map<String, String> containers = new ConcurrentHashMap<>();
    private final Map<String, WorkloadResult> results = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public DockerRuntimeProvider(ContainerManager containerManager, RuntimePolicy policy) {
        this.docker = containerManager.getDockerClient();
        this.policy = policy;
    }

    @Override
    public CompletableFuture<WorkloadResult> submit(WorkloadSpec spec, Consumer<WorkloadResult> events) {
        policy.validate(spec);
        WorkloadResult queued = event(spec, WorkloadResult.State.QUEUED, "", null,
                WorkloadResult.ErrorCategory.NONE, "Queued", null, null, List.of(), Map.of(), false);
        publish(queued, events);
        return CompletableFuture.supplyAsync(() -> execute(spec, events), executor);
    }

    private WorkloadResult execute(WorkloadSpec spec, Consumer<WorkloadResult> events) {
        Instant started = Instant.now();
        String containerId = "";
        List<String> logs = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            Files.createDirectories(spec.outputDirectory());
            ensureNetwork();
            String image = spec.profile().image().immutableReference();
            publish(event(spec, WorkloadResult.State.STARTING, "", null, WorkloadResult.ErrorCategory.NONE,
                    "Pulling " + image, started, null, List.of(), Map.of(), false), events);
            docker.pullImageCmd(image).exec(new PullImageResultCallback()).awaitCompletion(30, TimeUnit.MINUTES);

            List<Bind> binds = new ArrayList<>();
            for (WorkloadSpec.Mount mount : spec.mounts()) {
                binds.add(new Bind(mount.source().toAbsolutePath().normalize().toString(), new Volume(mount.target()),
                        mount.readOnly() ? AccessMode.ro : AccessMode.rw));
            }
            HostConfig host = HostConfig.newHostConfig()
                    .withBinds(binds)
                    .withMemory(spec.limits().memoryBytes())
                    .withNanoCPUs(spec.limits().nanoCpus())
                    .withPidsLimit((long) spec.limits().pids())
                    .withReadonlyRootfs(true)
                    .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,size=1g", "/var/tmp", "rw,nosuid,nodev,size=256m"))
                    .withCapDrop(Capability.ALL)
                    .withPrivileged(false)
                    .withNetworkMode(networkName)
                    .withExtraHosts("host.docker.internal:host-gateway")
                    .withSecurityOpts(List.of("no-new-privileges:true"));
            Map<String, String> labels = Map.of(
                    "localcloud.managed", "true",
                    "localcloud.service", spec.profile().technology(),
                    "localcloud.workload", spec.id(),
                    "localcloud.run", spec.runId(),
                    "localcloud.project", spec.projectId(),
                    "localcloud.profile", spec.profile().revisionId());
            List<String> env = new ArrayList<>();
            spec.profile().environment().forEach((key, value) -> env.add(key + "=" + value));
            spec.environment().forEach((key, value) -> env.add(key + "=" + value));

            var created = docker.createContainerCmd(image)
                    .withName("localcloud-job-" + safeName(spec.id()))
                    .withCmd(spec.command())
                    .withEnv(env)
                    .withLabels(labels)
                    .withHostConfig(host)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            containerId = created.getId();
            containers.put(spec.id(), containerId);
            final String runningId = containerId;
            docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true).withFollowStream(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override public void onNext(Frame frame) {
                            logs.add(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    });
            docker.startContainerCmd(containerId).exec();
            publish(event(spec, WorkloadResult.State.RUNNING, runningId, null, WorkloadResult.ErrorCategory.NONE,
                    "Running", started, null, List.copyOf(logs), Map.of(), false), events);

            Integer exit = waitFor(containerId, spec.timeout());
            Instant finished = Instant.now();
            boolean wasCancelled = cancelled.remove(spec.id());
            boolean success = !wasCancelled && exit != null && exit == 0;
            WorkloadResult.State finalState = wasCancelled ? WorkloadResult.State.CANCELLED
                    : success ? WorkloadResult.State.SUCCEEDED : WorkloadResult.State.FAILED;
            WorkloadResult.ErrorCategory category = wasCancelled ? WorkloadResult.ErrorCategory.CANCELLED
                    : success ? WorkloadResult.ErrorCategory.NONE : WorkloadResult.ErrorCategory.EXECUTION;
            Map<String, Double> metrics = Map.of(
                    "wallTimeSeconds", Duration.between(started, finished).toMillis() / 1000.0);
            WorkloadResult result = event(spec, finalState, containerId, exit, category,
                    wasCancelled ? "Cancelled" : success ? "Completed" : "Container exited with code " + exit,
                    started, finished, List.copyOf(logs), metrics, cleanup(containerId));
            publish(result, events);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            WorkloadResult result = event(spec, WorkloadResult.State.FAILED, containerId, null,
                    WorkloadResult.ErrorCategory.TIMEOUT, e.getMessage(), started, Instant.now(),
                    List.copyOf(logs), Map.of(), cleanup(containerId));
            publish(result, events);
            return result;
        } catch (RuntimePolicy.PolicyException e) {
            WorkloadResult result = event(spec, WorkloadResult.State.INFRA_ERROR, containerId, null,
                    WorkloadResult.ErrorCategory.POLICY, e.getMessage(), started, Instant.now(),
                    List.copyOf(logs), Map.of(), cleanup(containerId));
            publish(result, events);
            return result;
        } catch (Exception e) {
            boolean wasCancelled = cancelled.remove(spec.id());
            WorkloadResult result = event(spec,
                    wasCancelled ? WorkloadResult.State.CANCELLED : WorkloadResult.State.INFRA_ERROR,
                    containerId, null,
                    wasCancelled ? WorkloadResult.ErrorCategory.CANCELLED : WorkloadResult.ErrorCategory.AGENT,
                    wasCancelled ? "Cancelled" : e.getMessage(), started, Instant.now(),
                    List.copyOf(logs), Map.of(), cleanup(containerId));
            publish(result, events);
            return result;
        } finally {
            containers.remove(spec.id());
            cancelled.remove(spec.id());
        }
    }

    private void ensureNetwork() {
        boolean exists = docker.listNetworksCmd().withNameFilter(networkName).exec().stream()
                .anyMatch(network -> networkName.equals(network.getName()));
        if (!exists) docker.createNetworkCmd().withName(networkName).withInternal(true)
                .withCheckDuplicate(true).exec();
    }

    private Integer waitFor(String containerId, Duration timeout) throws Exception {
        WaitContainerResultCallback callback = docker.waitContainerCmd(containerId).exec(new WaitContainerResultCallback());
        Integer exit = callback.awaitStatusCode(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (exit == null) {
            docker.stopContainerCmd(containerId).withTimeout(10).exec();
            throw new java.util.concurrent.TimeoutException("workload timed out after " + timeout);
        }
        return exit;
    }

    private boolean cleanup(String containerId) {
        if (containerId == null || containerId.isBlank()) return true;
        try { docker.removeContainerCmd(containerId).withForce(true).exec(); return true; }
        catch (Exception ignored) { return false; }
    }

    private void publish(WorkloadResult result, Consumer<WorkloadResult> events) {
        results.put(result.workloadId(), result);
        events.accept(result);
    }

    private static WorkloadResult event(WorkloadSpec spec, WorkloadResult.State state, String runtimeId,
                                        Integer exitCode, WorkloadResult.ErrorCategory category, String message,
                                        Instant started, Instant finished, List<String> logs, Map<String, Double> metrics,
                                        boolean cleaned) {
        return new WorkloadResult(spec.id(), state, runtimeId, spec.profile().image().digest(), exitCode,
                category, message, started, finished, logs, metrics, cleaned);
    }

    private static String safeName(String value) { return value.replaceAll("[^a-zA-Z0-9_.-]", "-"); }

    @Override public boolean cancel(String workloadId) {
        String id = containers.get(workloadId);
        if (id == null) return false;
        cancelled.add(workloadId);
        try { docker.stopContainerCmd(id).withTimeout(10).exec(); return true; }
        catch (Exception e) { cancelled.remove(workloadId); return false; }
    }
    @Override public java.util.Optional<WorkloadResult> inspect(String workloadId) {
        return java.util.Optional.ofNullable(results.get(workloadId));
    }
    @Override public boolean available() { try { docker.pingCmd().exec(); return true; } catch (Exception e) { return false; } }
    @Override public String mode() { return "docker"; }
    @Override public void close() { executor.close(); }
}
