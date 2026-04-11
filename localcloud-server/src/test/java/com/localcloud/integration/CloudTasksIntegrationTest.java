package com.localcloud.integration;

import java.net.URI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tasks.v2.*;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.localcloud.emulators.cloudtasks.CloudTasksEmulator;
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
            testDs = TestDataSource.create("cloud_tasks_test");
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
    // gRPC Tests
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
    void createTask_gRPC() {
        // Create queue first
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/grpc-task-queue"))
                .build());

        // Create a task in the queue
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
    }

    @Test
    void listQueues_REST() throws Exception {
        // Create via gRPC first
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/rest-list-queue"))
                .build());

        // List via REST
        String url = baseUrl() + "/v2/" + parentPath() + "/queues";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertTrue(json.has("queues"), "Response should contain 'queues' array");
    }

    @Test
    void getQueue_REST() throws Exception {
        // Create via gRPC
        stub.createQueue(CreateQueueRequest.newBuilder()
                .setParent(parentPath())
                .setQueue(Queue.newBuilder()
                        .setName(parentPath() + "/queues/rest-get-queue"))
                .build());

        // Get via REST
        String url = baseUrl() + "/v2/" + parentPath() + "/queues/rest-get-queue";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals(parentPath() + "/queues/rest-get-queue", json.get("name").asText());
    }
}
