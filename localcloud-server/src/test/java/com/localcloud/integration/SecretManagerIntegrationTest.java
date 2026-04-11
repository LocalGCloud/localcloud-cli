package com.localcloud.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.secretmanager.v1.*;
import com.google.protobuf.ByteString;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.localcloud.emulators.secretmanager.SecretManagerEmulator;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Disabled;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Secret Manager emulator.
 * Starts a real Armeria server with gRPC + HTTP JSON transcoding enabled,
 * backed by an H2 in-memory database.
 *
 * <p>Tests verify both gRPC and REST access to the same underlying service.
 */
class SecretManagerIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static TestDataSource testDs;
    private static SecretManagerEmulator emulator;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            testDs = TestDataSource.create("secret_mgr_test");
            emulator = new SecretManagerEmulator(testDs.getDataSource());
            emulator.start();

            sb.service(GrpcService.builder()
                    .addService(emulator.getServiceImpl())
                    .enableHttpJsonTranscoding(true)
                    .build());
        }
    };

    private ManagedChannel channel;
    private SecretManagerServiceGrpc.SecretManagerServiceBlockingStub stub;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder
                .forAddress("127.0.0.1", server.httpPort())
                .usePlaintext()
                .build();
        stub = SecretManagerServiceGrpc.newBlockingStub(channel);
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // gRPC Tests
    // -----------------------------------------------------------------------

    @Test
    void createSecret_gRPC() {
        Secret secret = stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("grpc-create-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        assertEquals("projects/" + PROJECT + "/secrets/grpc-create-test", secret.getName());
    }

    @Test
    void getSecret_gRPC() {
        // Create first
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("grpc-get-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // Get
        Secret secret = stub.getSecret(GetSecretRequest.newBuilder()
                .setName("projects/" + PROJECT + "/secrets/grpc-get-test")
                .build());

        assertEquals("projects/" + PROJECT + "/secrets/grpc-get-test", secret.getName());
    }

    @Test
    void listSecrets_gRPC() {
        // Create two secrets
        for (String id : new String[]{"grpc-list-a", "grpc-list-b"}) {
            stub.createSecret(CreateSecretRequest.newBuilder()
                    .setParent("projects/" + PROJECT)
                    .setSecretId(id)
                    .setSecret(Secret.newBuilder()
                            .setReplication(Replication.newBuilder()
                                    .setAutomatic(Replication.Automatic.getDefaultInstance())))
                    .build());
        }

        ListSecretsResponse response = stub.listSecrets(ListSecretsRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .build());

        assertTrue(response.getSecretsCount() >= 2, "Expected at least 2 secrets");
    }

    @Test
    @Disabled("Requires PostgreSQL — H2 does not support INSERT...RETURNING with subquery")
    void addAndAccessSecretVersion_gRPC() {
        // Create secret
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("grpc-version-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // Add version with payload
        String payload = "my-secret-value";
        SecretVersion version = stub.addSecretVersion(AddSecretVersionRequest.newBuilder()
                .setParent("projects/" + PROJECT + "/secrets/grpc-version-test")
                .setPayload(SecretPayload.newBuilder()
                        .setData(ByteString.copyFromUtf8(payload)))
                .build());

        assertNotNull(version.getName());
        assertEquals(SecretVersion.State.ENABLED, version.getState());

        // Access the version
        AccessSecretVersionResponse accessResponse = stub.accessSecretVersion(
                AccessSecretVersionRequest.newBuilder()
                        .setName(version.getName())
                        .build());

        assertEquals(payload, accessResponse.getPayload().getData().toStringUtf8());
    }

    @Test
    void deleteSecret_gRPC() {
        // Create
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("grpc-delete-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // Delete
        stub.deleteSecret(DeleteSecretRequest.newBuilder()
                .setName("projects/" + PROJECT + "/secrets/grpc-delete-test")
                .build());

        // Verify NOT_FOUND
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getSecret(GetSecretRequest.newBuilder()
                        .setName("projects/" + PROJECT + "/secrets/grpc-delete-test")
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    @Test
    void getSecret_notFound_gRPC() {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getSecret(GetSecretRequest.newBuilder()
                        .setName("projects/" + PROJECT + "/secrets/nonexistent-secret")
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    // -----------------------------------------------------------------------
    // REST Tests (via gRPC-JSON transcoding)
    // -----------------------------------------------------------------------

    private String baseUrl() {
        return "http://127.0.0.1:" + server.httpPort();
    }

    @Test
    void createSecret_REST() throws Exception {
        String url = baseUrl() + "/v1/projects/" + PROJECT + "/secrets?secret_id=rest-create-test";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"replication\":{\"automatic\":{}}}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals("projects/" + PROJECT + "/secrets/rest-create-test", json.get("name").asText());
    }

    @Test
    void getSecret_REST() throws Exception {
        // Create via gRPC first
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("rest-get-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // Get via REST
        String url = baseUrl() + "/v1/projects/" + PROJECT + "/secrets/rest-get-test";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals("projects/" + PROJECT + "/secrets/rest-get-test", json.get("name").asText());
    }

    @Test
    void listSecrets_REST() throws Exception {
        // Create via gRPC
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("rest-list-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // List via REST
        String url = baseUrl() + "/v1/projects/" + PROJECT + "/secrets";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertTrue(json.has("secrets"), "Response should contain 'secrets' array");
    }

    @Test
    @Disabled("Requires PostgreSQL — H2 does not support INSERT...RETURNING with subquery")
    void addAndAccessSecretVersion_REST() throws Exception {
        // Create secret via gRPC
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("rest-version-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // Add version via REST
        String addUrl = baseUrl() + "/v1/projects/" + PROJECT + "/secrets/rest-version-test:addVersion";
        String payloadB64 = Base64.getEncoder().encodeToString("rest-secret-value".getBytes(StandardCharsets.UTF_8));
        HttpRequest addRequest = HttpRequest.newBuilder()
                .uri(URI.create(addUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"payload\":{\"data\":\"" + payloadB64 + "\"}}"))
                .build();

        HttpResponse<String> addResponse = httpClient.send(addRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, addResponse.statusCode());

        JsonNode addJson = mapper.readTree(addResponse.body());
        String versionName = addJson.get("name").asText();
        assertNotNull(versionName);

        // Access version via REST
        String accessUrl = baseUrl() + "/v1/" + versionName + ":access";
        HttpRequest accessRequest = HttpRequest.newBuilder()
                .uri(URI.create(accessUrl))
                .GET()
                .build();

        HttpResponse<String> accessResponse = httpClient.send(accessRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, accessResponse.statusCode());

        JsonNode accessJson = mapper.readTree(accessResponse.body());
        assertTrue(accessJson.has("payload"), "Response should contain 'payload'");
    }

    @Test
    void deleteSecret_REST() throws Exception {
        // Create via gRPC
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("rest-delete-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // Delete via REST
        String url = baseUrl() + "/v1/projects/" + PROJECT + "/secrets/rest-delete-test";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Verify NOT_FOUND via gRPC
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () ->
                stub.getSecret(GetSecretRequest.newBuilder()
                        .setName("projects/" + PROJECT + "/secrets/rest-delete-test")
                        .build()));
        assertEquals(io.grpc.Status.NOT_FOUND.getCode(), ex.getStatus().getCode());
    }

    // -----------------------------------------------------------------------
    // Cross-protocol Tests
    // -----------------------------------------------------------------------

    @Test
    void createViaGrpc_readViaRest() throws Exception {
        // Create via gRPC
        stub.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT)
                .setSecretId("cross-protocol-test")
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        // Read via REST
        String url = baseUrl() + "/v1/projects/" + PROJECT + "/secrets/cross-protocol-test";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        assertEquals("projects/" + PROJECT + "/secrets/cross-protocol-test", json.get("name").asText());
    }
}
