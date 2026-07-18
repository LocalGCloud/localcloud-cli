package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpService service;
    private ServiceRequestContext ctx;

    @BeforeEach
    void setUp() {
        ServiceRegistry registry = ServiceRegistry.load(8080);
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        when(config.getProjectId()).thenReturn("test-project");
        when(config.getGatewayPort()).thenReturn(8080);
        when(config.getServiceRegistry()).thenReturn(registry);
        when(config.isServiceEnabled(anyString())).thenAnswer(invocation -> {
            String serviceId = invocation.getArgument(0, String.class);
            var def = registry.getService(serviceId);
            return def != null && def.defaultEnabled();
        });
        when(config.isServiceDynamicallyEnabled(anyString())).thenAnswer(invocation -> {
            String serviceId = invocation.getArgument(0, String.class);
            var def = registry.getService(serviceId);
            return def != null && def.defaultEnabled();
        });
        when(config.getConfigSource(anyString())).thenReturn("test");

        service = new McpService(config, null, null, null, null, null, null);
        ctx = mock(ServiceRequestContext.class);
    }

    @Test
    void initializeReturnsMcpCapabilities() throws Exception {
        JsonNode response = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """);

        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(1, response.path("id").asInt());
        assertEquals(McpService.PROTOCOL_VERSION, response.path("result").path("protocolVersion").asText());
        assertTrue(response.path("result").path("capabilities").has("tools"));
        assertTrue(response.path("result").path("capabilities").has("resources"));
        assertTrue(response.path("result").path("capabilities").has("prompts"));
    }

    @Test
    void listsReadOnlyToolsAndHidesDestructiveToolsByDefault() throws Exception {
        JsonNode response = post("""
                {"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}
                """);

        String body = response.toString();
        assertTrue(body.contains("localcloud_list_services"));
        assertTrue(body.contains("localcloud_generate_terraform_env"));
        assertFalse(body.contains("localcloud_reset_project"));
        assertFalse(body.contains("localcloud_clear_faults"));
    }

    @Test
    void listServicesToolIncludesRegistryAndNoRealCloudFallback() throws Exception {
        JsonNode response = post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"localcloud_list_services","arguments":{}}}
                """);

        String text = response.path("result").path("content").get(0).path("text").asText();
        JsonNode payload = MAPPER.readTree(text);
        assertTrue(payload.path("services").isArray());
        assertTrue(text.contains("gcs"));
        assertFalse(payload.path("real_google_cloud_fallback").asBoolean(true));
    }

    @Test
    void readsEnvironmentResource() throws Exception {
        JsonNode response = post("""
                {"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"localcloud://env/json"}}
                """);

        String text = response.path("result").path("contents").get(0).path("text").asText();
        JsonNode env = MAPPER.readTree(text);
        assertEquals("test-project", env.path("GOOGLE_CLOUD_PROJECT").asText());
        assertTrue(env.has("STORAGE_EMULATOR_HOST"));
    }

    @Test
    void destructiveToolCallIsRejectedByDefault() throws Exception {
        JsonNode response = post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"localcloud_reset_project","arguments":{}}}
                """);

        JsonNode result = response.path("result");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("LOCALCLOUD_MCP_DESTRUCTIVE=true"));
    }

    @Test
    void promptGetReturnsMessages() throws Exception {
        JsonNode response = post("""
                {"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{"name":"terraform-with-localcloud"}}
                """);

        JsonNode messages = response.path("result").path("messages");
        assertTrue(messages.isArray());
        assertEquals("user", messages.get(0).path("role").asText());
        assertTrue(messages.get(0).path("content").path("text").asText().contains("localcloud_generate_terraform_env"));
    }

    @Test
    void rejectsRemoteOriginByDefault() throws Exception {
        AggregatedHttpRequest request = AggregatedHttpRequest.of(
                HttpMethod.POST, "/mcp", MediaType.JSON,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}")
                .toHttpRequest()
                .aggregate()
                .join();
        request = AggregatedHttpRequest.of(
                com.linecorp.armeria.common.RequestHeaders.of(
                        HttpMethod.POST, "/mcp",
                        com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE, MediaType.JSON,
                        com.linecorp.armeria.common.HttpHeaderNames.ORIGIN, "https://example.com"),
                request.content());

        var response = service.post(ctx, request).aggregate().join();

        assertEquals(HttpStatus.FORBIDDEN, response.status());
    }

    private JsonNode post(String body) throws Exception {
        AggregatedHttpRequest request = AggregatedHttpRequest.of(HttpMethod.POST, "/mcp", MediaType.JSON, body);
        var response = service.post(ctx, request).aggregate().join();
        assertEquals(HttpStatus.OK, response.status());
        return MAPPER.readTree(response.contentUtf8());
    }
}
