package com.localcloud.integration;

import java.net.URI;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tasks.v2.*;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.localcloud.emulators.cloudtasks.CloudTasksEmulator;
import com.localcloud.emulators.cloudtasks.CloudTasksStore;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Cloud Tasks emulator.
 * Starts a real Armeria server with gRPC + HTTP JSON transcoding enabled,
 * backed by an H2 in-memory database.
 */
class CloudTasksIntegrationTest {

    private static final String PROJECT = "tasks-test-project";
    private static final String LOCATION = "us-central1";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static TestDataSource testDs;
    private static CloudTasksEmulator emulator;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            testDs = TestDataSource.create("cloud_tasks_integration_test");
            emulator = new CloudTasksEmulator(testDs.getDataSource());
            emulator.start();

            sb.service(GrpcService.builder()
                    .addService(emulator.getServiceImpl())
                    .enableHttpJsonTranscoding(true)
                    .build());
        }
    };

    private ManagedChannel channel;
    private CloudTasksGrpc.CloudTasksBlockingStub stub;
    private java.net.http.HttpClient httpClient;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder
                .forAddress("127.0.0.1", server.httpPort())
                .usePlaintext()
                .build();
        stub = CloudTasksGrpc.newBlockingStub(channel);
        httpClient = java.net.http.HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    private String parentPath() {
        return "projects/" + PROJECT + "/locations/" + LOCATION;
    }

    // -----------------------------------------------------------------------
    // gRPC Queue Tests
    // -----------------------------------------------------------------------

    @Test
    void createQueue_gRPC() {
        Queue queue = stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-queue-1"))
                .build());

        assertEquals(parentPath() + "/queues/grpc-queue-1", queue.getName());
        assertEquals(Queue.State.RUNNING, queue.getState());
    }

    @Test
    void createQueue_withRateLimits_gRPC() {
        Queue queue = stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/rate-limited-queue")
                        .setRateLimits(RateLimits.newBuilder()
                                .setMaxDispatchesPerSecond(100)
                                .setMaxConcurrentDispatches(50)
                                .setMaxBurstSize(10)
                                .build()))
                .build());

        assertEquals(parentPath() + "/queues/rate-limited-queue", queue.getName());
        assertTrue(queue.hasRateLimits());
        assertEquals(100, queue.getRateLimits().getMaxDispatchesPerSecond(), 0.01);
        assertEquals(50, queue.getRateLimits().getMaxConcurrentDispatches());
        assertEquals(10, queue.getRateLimits().getMaxBurstSize());
    }

    @Test
    void createQueue_withRetryConfig_gRPC() {
        Queue queue = stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/retry-config-queue")
                        .setRetryConfig(RetryConfig.newBuilder()
                                .setMaxAttempts(5)
                                .setMinBackoff(Duration.newBuilder().setSeconds(2).build())
                                .setMaxBackoff(Duration.newBuilder().setSeconds(120).build())
                                .setMaxDoublings(3)
                                .build()))
                .build());

        assertEquals(parentPath() + "/queues/retry-config-queue", queue.getName());
        assertTrue(queue.hasRetryConfig());
        assertEquals(5, queue.getRetryConfig().getMaxAttempts());
        assertEquals(3, queue.getRetryConfig().getMaxDoublings());
        assertEquals(2, queue.getRetryConfig().getMinBackoff().getSeconds());
        assertEquals(120, queue.getRetryConfig().getMaxBackoff().getSeconds());
    }

    @Test
    void getQueue_gRPC() {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-get-queue"))
                .build());

        Queue queue = stub.getQueue(GetQueueRequest.newBuilder()
                .setName(parentPath() + "/queues/grpc-get-queue")
                .build());

        assertEquals(parentPath() + "/queues/grpc-get-queue", queue.getName());
    }

    @Test
    void listQueues_gRPC() {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-list-q1"))
                .build());
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-list-q2"))
                .build());

        ListQueuesResponse response = stub.listQueues(ListQueuesRequest.newBuilder()
                .setParent(parentPath())
                .build());

        assertTrue(response.getQueuesCount() >= 2, "Expected at least 2 queues");
    }

    @Test
    void deleteQueue_gRPC() {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-delete-queue"))
                .build());

        stub.deleteQueue(DeleteQueueRequest.newBuilder()
                .setName(parentPath() + "/queues/grpc-delete-queue")
                .build());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getQueue(GetQueueRequest.newBuilder()
                        .setName(parentPath() + "/queues/grpc-delete-queue")
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    @Test
    void updateQueue_gRPC_full() {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-update-queue"))
                .build());

        Queue updated = stub.updateQueue(UpdateQueueRequest.newBuilder()
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-update-queue")
                        .setRetryConfig(RetryConfig.newBuilder()
                                .setMaxAttempts(5)
                                .setMaxDoublings(2)
                                .build())
                        .setRateLimits(RateLimits.newBuilder()
                                .setMaxDispatchesPerSecond(50)
                                .build()))
                .build());

        assertEquals(5, updated.getRetryConfig().getMaxAttempts());
        assertEquals(2, updated.getRetryConfig().getMaxDoublings());
        assertEquals(50, updated.getRateLimits().getMaxDispatchesPerSecond(), 0.01);
    }

    @Test
    void updateQueue_gRPC_partialFieldMask() {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-update-mask-queue"))
                .build());

        // Only update maxAttempts, leave other fields unchanged
        Queue updated = stub.updateQueue(UpdateQueueRequest.newBuilder()
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-update-mask-queue")
                        .setRetryConfig(RetryConfig.newBuilder()
                                .setMaxAttempts(3)
                                .setMaxDoublings(99) // should be ignored
                                .build()))
                .setUpdateMask(com.google.protobuf.FieldMask.newBuilder()
                        .addPaths("retry_config.max_attempts")
                        .build())
                .build());

        assertEquals(3, updated.getRetryConfig().getMaxAttempts());
        // maxDoublings should still be default (16), not 99
        assertEquals(16, updated.getRetryConfig().getMaxDoublings());
    }

    @Test
    void purgeQueue_gRPC() {
        String queueName = parentPath() + "/queues/grpc-purge-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        // Create some tasks
        stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        Queue purged = stub.purgeQueue(PurgeQueueRequest.newBuilder()
                .setName(queueName)
                .build());

        assertNotNull(purged);
        assertEquals(queueName, purged.getName());

        // Tasks should be gone
        ListTasksResponse taskList = stub.listTasks(ListTasksRequest.newBuilder()
                .setParent(queueName)
                .build());
        assertEquals(0, taskList.getTasksCount());
    }

    @Test
    void pauseAndResumeQueue_gRPC() {
        String queueName = parentPath() + "/queues/grpc-pause-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        Queue paused = stub.pauseQueue(PauseQueueRequest.newBuilder().setName(queueName).build());
        assertEquals(Queue.State.PAUSED, paused.getState());

        Queue resumed = stub.resumeQueue(ResumeQueueRequest.newBuilder().setName(queueName).build());
        assertEquals(Queue.State.RUNNING, resumed.getState());
    }

    // -----------------------------------------------------------------------
    // Task Tests (gRPC)
    // -----------------------------------------------------------------------

    @Test
    void createTask_gRPC() {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-task-queue"))
                .build());

        Task task = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(parentPath() + "/queues/grpc-task-queue")
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        assertNotNull(task.getName());
        assertTrue(task.getName().contains("grpc-task-queue/tasks/"));
    }

    @Test
    void createTask_withScheduleTime_gRPC() {
        String queueName = parentPath() + "/queues/scheduled-task-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        Instant future = Instant.now().plusSeconds(3600);
        Task task = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setScheduleTime(Timestamp.newBuilder()
                                .setSeconds(future.getEpochSecond())
                                .build())
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        assertTrue(task.hasScheduleTime());
        assertEquals(future.getEpochSecond(), task.getScheduleTime().getSeconds());
    }

    @Test
    void createTask_withDispatchDeadline_gRPC() {
        String queueName = parentPath() + "/queues/deadline-task-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        Task task = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setDispatchDeadline(Duration.newBuilder().setSeconds(600).build())
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        assertNotNull(task.getName());
        assertTrue(task.hasDispatchDeadline());
    }

    @Test
    void getTask_gRPC() {
        String queueName = parentPath() + "/queues/get-task-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        Task created = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        Task retrieved = stub.getTask(GetTaskRequest.newBuilder()
                .setName(created.getName())
                .build());

        assertEquals(created.getName(), retrieved.getName());
        assertTrue(retrieved.hasCreateTime());
        assertTrue(retrieved.hasHttpRequest());
    }

    @Test
    void listTasks_gRPC() {
        String queueName = parentPath() + "/queues/list-task-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/task1")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/task2")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        ListTasksResponse response = stub.listTasks(ListTasksRequest.newBuilder()
                .setParent(queueName)
                .build());

        assertEquals(2, response.getTasksCount());
    }

    @Test
    void deleteTask_gRPC() {
        String queueName = parentPath() + "/queues/delete-task-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        Task created = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        stub.deleteTask(DeleteTaskRequest.newBuilder().setName(created.getName()).build());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getTask(GetTaskRequest.newBuilder().setName(created.getName()).build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    @Test
    void runTask_gRPC() {
        String queueName = parentPath() + "/queues/run-task-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        Task created = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        Task run = stub.runTask(RunTaskRequest.newBuilder().setName(created.getName()).build());
        assertNotNull(run);
        assertEquals(created.getName(), run.getName());
    }

    @Test
    void taskPersistence_acrossReset() {
        String queueName = parentPath() + "/queues/persist-task-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        Task created = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        // Task should be retrievable
        Task retrieved = stub.getTask(GetTaskRequest.newBuilder().setName(created.getName()).build());
        assertNotNull(retrieved);

        // Reset the emulator
        emulator.reset();

        // After reset, task should be gone (DB cleared)
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getTask(GetTaskRequest.newBuilder().setName(created.getName()).build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    // -----------------------------------------------------------------------
    // REST Tests (via gRPC-JSON transcoding)
    // -----------------------------------------------------------------------

    private String baseUrl() {
        return "http://127.0.0.1:" + server.httpPort();
    }

    @Test
    void createQueue_REST() throws Exception {
        String url = baseUrl() + "/v2/" + parentPath() + "/queues";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\"" + parentPath() + "/queues/rest-queue-1\"}"))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals(parentPath() + "/queues/rest-queue-1", json.get("name").asText());
        assertTrue(json.has("rateLimits"));
        assertTrue(json.has("retryConfig"));
    }

    @Test
    void createQueue_withRetryConfig_REST() throws Exception {
        String url = baseUrl() + "/v2/" + parentPath() + "/queues";
        String body = "{\"name\":\"" + parentPath() + "/queues/rest-retry-queue\","
                + "\"retryConfig\":{"
                + "\"maxAttempts\":3,"
                + "\"minBackoff\":\"1s\","
                + "\"maxBackoff\":\"60s\","
                + "\"maxDoublings\":5"
                + "}}";

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals(3, json.get("retryConfig").get("maxAttempts").asInt());
        assertEquals("1s", json.get("retryConfig").get("minBackoff").asText());
        assertEquals("60s", json.get("retryConfig").get("maxBackoff").asText());
    }

    @Test
    void listQueues_REST() throws Exception {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/rest-list-queue"))
                .build());

        String url = baseUrl() + "/v2/" + parentPath() + "/queues";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertTrue(json.has("queues"), "Response should contain 'queues' array");
        assertTrue(json.get("queues").size() > 0);

        // Each queue should have rateLimits and retryConfig
        JsonNode first = json.get("queues").get(0);
        assertTrue(first.has("rateLimits"));
        assertTrue(first.has("retryConfig"));
    }

    @Test
    void getQueue_REST() throws Exception {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/rest-get-queue"))
                .build());

        String url = baseUrl() + "/v2/" + parentPath() + "/queues/rest-get-queue";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals(parentPath() + "/queues/rest-get-queue", json.get("name").asText());
        assertTrue(json.has("rateLimits"));
        assertTrue(json.has("retryConfig"));
    }

    @Test
    void updateQueue_REST() throws Exception {
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/rest-update-queue"))
                .build());

        String url = baseUrl() + "/v2/" + parentPath() + "/queues/rest-update-queue";
        String body = "{\"retryConfig\":{\"maxAttempts\":7}}";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals(7, json.get("retryConfig").get("maxAttempts").asInt());
    }

    @Test
    void purgeQueue_REST() throws Exception {
        String queueName = parentPath() + "/queues/rest-purge-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        // Add a task via gRPC
        stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/handler")
                                .setHttpMethod(HttpMethod.POST)))
                .build());

        // Purge via REST
        String url = baseUrl() + "/v2/" + queueName + ":purge";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Verify tasks are gone
        ListTasksResponse taskList = stub.listTasks(ListTasksRequest.newBuilder()
                .setParent(queueName)
                .build());
        assertEquals(0, taskList.getTasksCount());
    }

    // -----------------------------------------------------------------------
    // Error Cases
    // -----------------------------------------------------------------------

    @Test
    void createQueue_duplicate_gRPC() {
        String queueName = parentPath() + "/queues/dup-queue";
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder().setName(queueName))
                .build());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.createQueue(CreateQueueRequest.newBuilder()
                        .setParent(parentPath())
                        .setQueue(Queue.newBuilder().setName(queueName))
                        .build()));
        assertEquals(io.grpc.Status.ALREADY_EXISTS.getCode(), ex.getStatus().getCode());
    }

    @Test
    void getQueue_notFound_gRPC() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getQueue(GetQueueRequest.newBuilder()
                        .setName(parentPath() + "/queues/nonexistent")
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    @Test
    void createTask_queueNotFound_gRPC() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.createTask(CreateTaskRequest.newBuilder()
                        .setParent(parentPath() + "/queues/nonexistent")
                        .setTask(Task.newBuilder()
                                .setHttpRequest(HttpRequest.newBuilder()
                                        .setUrl("https://example.com/handler")
                                        .setHttpMethod(HttpMethod.POST)))
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    @Test
    void updateQueue_notFound_gRPC() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.updateQueue(UpdateQueueRequest.newBuilder()
                        .setQueue(Queue.newBuilder()
                                .setName(parentPath() + "/queues/nonexistent"))
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    @Test
    void purgeQueue_notFound_gRPC() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.purgeQueue(PurgeQueueRequest.newBuilder()
                        .setName(parentPath() + "/queues/nonexistent")
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    // -----------------------------------------------------------------------
    // Full Lifecycle Test
    // -----------------------------------------------------------------------

    @Test
    void fullLifecycle_gRPC() {
        String queueName = parentPath() + "/queues/lifecycle-queue";

        // 1. Create queue with config
        Queue created = stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(queueName)
                        .setRetryConfig(RetryConfig.newBuilder()
                                .setMaxAttempts(3)
                                .setMaxDoublings(4)
                                .build())
                        .setRateLimits(RateLimits.newBuilder()
                                .setMaxDispatchesPerSecond(10)
                                .build()))
                .build());
        assertEquals(3, created.getRetryConfig().getMaxAttempts());

        // 2. Update queue
        Queue updated = stub.updateQueue(UpdateQueueRequest.newBuilder()
                .setQueue(Queue.newBuilder()
                        .setName(queueName)
                        .setRetryConfig(RetryConfig.newBuilder()
                                .setMaxAttempts(5)
                                .build()))
                .setUpdateMask(com.google.protobuf.FieldMask.newBuilder()
                        .addPaths("retry_config.max_attempts")
                        .build())
                .build());
        assertEquals(5, updated.getRetryConfig().getMaxAttempts());
        assertEquals(4, updated.getRetryConfig().getMaxDoublings()); // unchanged

        // 3. Create tasks
        Task task1 = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/task1")
                                .setHttpMethod(HttpMethod.POST)))
                .build());
        assertNotNull(task1);

        Task task2 = stub.createTask(CreateTaskRequest.newBuilder()
                .setParent(queueName)
                .setTask(Task.newBuilder()
                        .setHttpRequest(HttpRequest.newBuilder()
                                .setUrl("https://example.com/task2")
                                .setHttpMethod(HttpMethod.POST)))
                .build());
        assertNotNull(task2);

        // 4. List tasks
        ListTasksResponse taskList = stub.listTasks(ListTasksRequest.newBuilder()
                .setParent(queueName)
                .build());
        assertEquals(2, taskList.getTasksCount());

        // 5. Get specific task
        Task retrieved = stub.getTask(GetTaskRequest.newBuilder()
                .setName(task1.getName())
                .build());
        assertEquals(task1.getName(), retrieved.getName());
        assertTrue(retrieved.hasCreateTime());
        assertTrue(retrieved.hasScheduleTime());

        // 6. Delete task
        stub.deleteTask(DeleteTaskRequest.newBuilder().setName(task1.getName()).build());
        ListTasksResponse afterDelete = stub.listTasks(ListTasksRequest.newBuilder()
                .setParent(queueName)
                .build());
        assertEquals(1, afterDelete.getTasksCount());

        // 7. Pause queue
        stub.pauseQueue(PauseQueueRequest.newBuilder().setName(queueName).build());
        Queue paused = stub.getQueue(GetQueueRequest.newBuilder().setName(queueName).build());
        assertEquals(Queue.State.PAUSED, paused.getState());

        // 8. Resume queue
        stub.resumeQueue(ResumeQueueRequest.newBuilder().setName(queueName).build());
        Queue resumed = stub.getQueue(GetQueueRequest.newBuilder().setName(queueName).build());
        assertEquals(Queue.State.RUNNING, resumed.getState());

        // 9. Purge queue
        stub.purgeQueue(PurgeQueueRequest.newBuilder().setName(queueName).build());
        ListTasksResponse afterPurge = stub.listTasks(ListTasksRequest.newBuilder()
                .setParent(queueName)
                .build());
        assertEquals(0, afterPurge.getTasksCount());

        // 10. Delete queue
        stub.deleteQueue(DeleteQueueRequest.newBuilder().setName(queueName).build());
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getQueue(GetQueueRequest.newBuilder().setName(queueName).build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }
}
