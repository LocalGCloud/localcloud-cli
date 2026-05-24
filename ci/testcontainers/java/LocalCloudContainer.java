package com.example.localcloud;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers helper for running LocalCloud in JVM integration tests.
 *
 * <p>Copy this helper into a test source set that has Testcontainers on the
 * classpath, then use {@link #endpointEnvironment()} to configure Google Cloud
 * SDK clients against the container's mapped ports.</p>
 */
public class LocalCloudContainer extends GenericContainer<LocalCloudContainer> {

    private static final DockerImageName DEFAULT_IMAGE = DockerImageName.parse("localcloud/localcloud:latest");

    private static final int GATEWAY_PORT = 8080;
    private static final int GCS_PORT = 4443;
    private static final int PUBSUB_PORT = 8085;
    private static final int FIRESTORE_PORT = 8086;
    private static final int BIGTABLE_PORT = 8087;
    private static final int SPANNER_GRPC_PORT = 9010;
    private static final int SPANNER_REST_PORT = 9020;
    private static final int BIGQUERY_PORT = 9050;
    private static final int MEMORYSTORE_PORT = 6379;

    private final String projectId;

    public LocalCloudContainer() {
        this(DEFAULT_IMAGE, "local-project", "core");
    }

    public LocalCloudContainer(DockerImageName image, String projectId, String profile) {
        super(image);
        this.projectId = projectId;
        withExposedPorts(
                GATEWAY_PORT,
                GCS_PORT,
                PUBSUB_PORT,
                FIRESTORE_PORT,
                BIGTABLE_PORT,
                SPANNER_GRPC_PORT,
                SPANNER_REST_PORT,
                BIGQUERY_PORT,
                MEMORYSTORE_PORT);
        withEnv("LOCALCLOUD_PROJECT", projectId);
        withEnv("LOCALCLOUD_PROFILE", profile);
        withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(4L * 1024L * 1024L * 1024L));
        waitingFor(new HttpWaitStrategy()
                .forPath("/readiness")
                .forPort(GATEWAY_PORT)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    public String gatewayEndpoint() {
        return endpoint(GATEWAY_PORT);
    }

    public Map<String, String> endpointEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GOOGLE_CLOUD_PROJECT", projectId);
        env.put("GCLOUD_PROJECT", projectId);
        env.put("CLOUDSDK_CORE_PROJECT", projectId);
        env.put("CLOUDSDK_AUTH_ACCESS_TOKEN", "localcloud-dev-token");
        env.put("STORAGE_EMULATOR_HOST", endpoint(GCS_PORT));
        env.put("PUBSUB_EMULATOR_HOST", hostPort(PUBSUB_PORT));
        env.put("FIRESTORE_EMULATOR_HOST", hostPort(FIRESTORE_PORT));
        env.put("BIGTABLE_EMULATOR_HOST", hostPort(BIGTABLE_PORT));
        env.put("SPANNER_EMULATOR_HOST", hostPort(SPANNER_GRPC_PORT));
        env.put("BIGQUERY_EMULATOR_HOST", endpoint(BIGQUERY_PORT));
        env.put("REDIS_HOST", getHost());
        env.put("REDIS_PORT", String.valueOf(getMappedPort(MEMORYSTORE_PORT)));
        env.put("LOCALCLOUD_GATEWAY_URL", gatewayEndpoint());
        return env;
    }

    public String diagnosticsArchiveUrl() {
        return gatewayEndpoint() + "/diagnostics/archive?limit=200";
    }

    private String endpoint(int port) {
        return "http://" + hostPort(port);
    }

    private String hostPort(int port) {
        return getHost() + ":" + getMappedPort(port);
    }
}
