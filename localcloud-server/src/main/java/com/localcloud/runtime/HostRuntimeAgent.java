package com.localcloud.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.docker.ContainerManager;
import com.localcloud.docker.DockerClientProvider;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone host process that polls LocalCloud and is the only component that touches Docker. */
public final class HostRuntimeAgent {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String controlUrl;
    private final String token;
    private final String agentId;
    private final Set<String> capabilities;
    private final DockerRuntimeProvider runtime;
    private final AtomicInteger active = new AtomicInteger();
    private final int maxParallel = Integer.parseInt(env("LOCALCLOUD_RUNTIME_MAX_PARALLEL", "2"));

    private HostRuntimeAgent() throws Exception {
        controlUrl = env("LOCALCLOUD_CONTROL_URL", "http://127.0.0.1:24080").replaceAll("/$", "");
        token = requiredEnv("LOCALCLOUD_RUNTIME_AGENT_TOKEN");
        agentId = env("LOCALCLOUD_RUNTIME_AGENT_ID", InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID());
        capabilities = Set.copyOf(split(env("LOCALCLOUD_RUNTIME_CAPABILITIES", "spark,pyspark,spark-sql,hadoop,hive")));
        List<Path> roots = split(env("LOCALCLOUD_RUNTIME_WORKSPACES", System.getProperty("user.home") + ",/tmp/localcloud"))
                .stream().map(Path::of).toList();
        ContainerManager containers = new ContainerManager(DockerClientProvider.getClient());
        containers.listByLabel("localcloud.service", "dataproc").forEach(container -> {
            containers.stop(container.getId());
            containers.remove(container.getId());
        });
        runtime = new DockerRuntimeProvider(containers, new RuntimePolicy(roots));
    }

    private void run() throws Exception {
        register();
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        while (!Thread.currentThread().isInterrupted()) {
            try {
                processCommands();
                if (active.get() >= maxParallel) {
                    Thread.sleep(500);
                    continue;
                }
                RuntimeAgentProtocol.WorkItem item = poll();
                if (item == null) {
                    Thread.sleep(750);
                    continue;
                }
                WorkloadSpec spec = item.toSpec();
                active.incrementAndGet();
                try {
                    runtime.submit(spec, this::postEvent).whenComplete((result, failure) -> {
                        try {
                            if (failure != null) postEvent(agentFailure(spec, failure));
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                } catch (Exception failure) {
                    active.decrementAndGet();
                    postEvent(agentFailure(spec, failure));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception transientFailure) {
                System.err.println("Runtime agent poll failed: " + transientFailure.getMessage());
                Thread.sleep(2000);
                register();
            }
        }
    }

    private static WorkloadResult agentFailure(WorkloadSpec spec, Throwable failure) {
        return new WorkloadResult(spec.id(), WorkloadResult.State.INFRA_ERROR,
                "", spec.profile().image().digest(), null, WorkloadResult.ErrorCategory.AGENT,
                failure.getMessage(), null, java.time.Instant.now(), List.of(), java.util.Map.of(), false);
    }

    private void register() throws Exception {
        RuntimeAgentProtocol.Registration request = new RuntimeAgentProtocol.Registration(agentId, capabilities, List.of());
        HttpResponse<String> response = post("/runtime/agent/register", request);
        requireSuccess(response);
        RuntimeAgentProtocol.RegistrationResult result = mapper.readValue(response.body(), RuntimeAgentProtocol.RegistrationResult.class);
        result.cancelWorkloadIds().forEach(runtime::cancel);
        System.out.println("Runtime agent registered as " + agentId + " with " + capabilities);
    }

    private RuntimeAgentProtocol.WorkItem poll() throws Exception {
        HttpResponse<String> response = post("/runtime/agent/poll", new RuntimeAgentProtocol.Poll(agentId, capabilities));
        if (response.statusCode() == 204) return null;
        requireSuccess(response);
        return mapper.readValue(response.body(), RuntimeAgentProtocol.WorkItem.class);
    }

    private void processCommands() throws Exception {
        HttpRequest request = request("/runtime/agent/commands?agentId=" + java.net.URLEncoder.encode(agentId, java.nio.charset.StandardCharsets.UTF_8))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response);
        RuntimeAgentProtocol.Commands commands = mapper.readValue(response.body(), RuntimeAgentProtocol.Commands.class);
        commands.cancelWorkloadIds().forEach(runtime::cancel);
    }

    private void postEvent(WorkloadResult event) {
        try {
            requireSuccess(post("/runtime/agent/events", event));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to report workload event", e);
        }
    }

    private HttpResponse<String> post(String path, Object value) throws Exception {
        HttpRequest request = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(controlUrl + path)).timeout(Duration.ofSeconds(35))
                .header("Authorization", "Bearer " + token);
    }

    private static void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Control plane returned " + response.statusCode() + ": " + response.body());
        }
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public static void main(String[] args) throws Exception { new HostRuntimeAgent().run(); }
}
