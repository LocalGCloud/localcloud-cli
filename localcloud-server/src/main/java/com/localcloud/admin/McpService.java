package com.localcloud.admin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;

/**
 * Model Context Protocol endpoint for agents that need to use LocalCloud instead
 * of real Google Cloud resources.
 */
public class McpService {

    static final String PROTOCOL_VERSION = "2025-11-25";

    private static final Set<String> WRITE_TOOLS = Set.of(
            "localcloud_seed_project",
            "localcloud_import_state");
    private static final Set<String> DESTRUCTIVE_TOOLS = Set.of(
            "localcloud_reset_project",
            "localcloud_reset_service",
            "localcloud_create_fault",
            "localcloud_clear_faults");

    private final LocalCloudConfig config;
    private final BrowseService browseService;
    private final DiagnosticsService diagnosticsService;
    private final SeedService seedService;
    private final ExportService exportService;
    private final QueryService queryService;
    private final FaultInjectionService faultInjectionService;
    private final ObjectMapper mapper;
    private final boolean writeEnabled;
    private final boolean destructiveEnabled;
    private final boolean remoteAllowed;

    public McpService(LocalCloudConfig config,
                      BrowseService browseService,
                      DiagnosticsService diagnosticsService,
                      SeedService seedService,
                      ExportService exportService,
                      QueryService queryService,
                      FaultInjectionService faultInjectionService) {
        this.config = config;
        this.browseService = browseService;
        this.diagnosticsService = diagnosticsService;
        this.seedService = seedService;
        this.exportService = exportService;
        this.queryService = queryService;
        this.faultInjectionService = faultInjectionService;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.writeEnabled = Boolean.parseBoolean(System.getenv().getOrDefault("LOCALCLOUD_MCP_WRITE", "false"));
        this.destructiveEnabled = Boolean.parseBoolean(System.getenv().getOrDefault("LOCALCLOUD_MCP_DESTRUCTIVE", "false"));
        this.remoteAllowed = Boolean.parseBoolean(System.getenv().getOrDefault("LOCALCLOUD_MCP_ALLOW_REMOTE", "false"));
    }

    @Get("/mcp")
    public HttpResponse get(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        if (!isAllowedRequest(ctx, request)) {
            return forbidden();
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "localcloud-mcp");
        info.put("protocolVersion", PROTOCOL_VERSION);
        info.put("transport", "streamable-http");
        info.put("endpoint", "/mcp");
        info.put("capabilities", capabilities());
        info.put("safety", safetyState());
        return json(HttpStatus.OK, info);
    }

    @Post("/mcp")
    public HttpResponse post(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        if (!isAllowedRequest(ctx, request)) {
            return forbidden();
        }
        try {
            Object payload = mapper.readValue(request.contentUtf8(), Object.class);
            if (payload instanceof List<?> batch) {
                if (batch.isEmpty()) {
                    return json(HttpStatus.OK, error(null, -32600, "Invalid Request", "Batch must not be empty"));
                }
                List<Object> responses = new ArrayList<>();
                for (Object item : batch) {
                    Object response = handleJsonRpc(ctx, item);
                    if (response != null) {
                        responses.add(response);
                    }
                }
                return responses.isEmpty()
                        ? HttpResponse.of(HttpStatus.ACCEPTED)
                        : json(HttpStatus.OK, responses);
            }
            Object response = handleJsonRpc(ctx, payload);
            return response == null ? HttpResponse.of(HttpStatus.ACCEPTED) : json(HttpStatus.OK, response);
        } catch (Exception e) {
            return json(HttpStatus.OK, error(null, -32700, "Parse error", e.getMessage()));
        }
    }

    private Object handleJsonRpc(ServiceRequestContext ctx, Object payload) throws Exception {
        if (!(payload instanceof Map<?, ?> raw)) {
            return error(null, -32600, "Invalid Request", "JSON-RPC payload must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) raw;
        Object id = request.get("id");
        String method = string(request.get("method"));
        if (method == null || method.isBlank()) {
            return error(id, -32600, "Invalid Request", "Missing method");
        }
        if (id == null && method.startsWith("notifications/")) {
            return null;
        }
        try {
            Object result = switch (method) {
                case "initialize" -> initializeResult();
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> callTool(ctx, params(request));
                case "resources/list" -> Map.of("resources", resources());
                case "resources/templates/list" -> Map.of("resourceTemplates", resourceTemplates());
                case "resources/read" -> readResource(params(request));
                case "prompts/list" -> Map.of("prompts", prompts());
                case "prompts/get" -> getPrompt(params(request));
                default -> throw new McpException(-32601, "Method not found", method);
            };
            return success(id, result);
        } catch (McpException e) {
            return error(id, e.code, e.mcpMessage, e.getMessage());
        }
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "localcloud");
        serverInfo.put("version", "0.1.0");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities());
        result.put("serverInfo", serverInfo);
        result.put("instructions", "Use LocalCloud endpoints and compatibility resources before attempting Google Cloud operations. Do not fall back to real Google Cloud from this MCP server.");
        return result;
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("subscribe", false);
        resources.put("listChanged", false);

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("listChanged", false);

        Map<String, Object> prompts = new LinkedHashMap<>();
        prompts.put("listChanged", false);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("resources", resources);
        capabilities.put("tools", tools);
        capabilities.put("prompts", prompts);
        return capabilities;
    }

    private Map<String, Object> safetyState() {
        Map<String, Object> safety = new LinkedHashMap<>();
        safety.put("mode", writeEnabled ? "write-enabled" : "read-only");
        safety.put("write_enabled", writeEnabled);
        safety.put("destructive_enabled", destructiveEnabled);
        safety.put("remote_allowed", remoteAllowed);
        safety.put("real_google_cloud_fallback", false);
        return safety;
    }

    private List<Map<String, Object>> resources() {
        List<Map<String, Object>> resources = new ArrayList<>();
        resources.add(resource("localcloud://services", "LocalCloud services", "All known LocalCloud services, endpoints, status, env vars, and safety metadata", "application/json"));
        resources.add(resource("localcloud://env/shell", "Shell environment", "Shell exports for SDKs and gcloud", "text/plain"));
        resources.add(resource("localcloud://env/json", "JSON environment", "JSON object of LocalCloud environment variables", "application/json"));
        resources.add(resource("localcloud://env/terraform", "Terraform environment", "Terraform Google provider custom endpoint exports", "text/plain"));
        resources.add(resource("localcloud://readiness", "Readiness", "Global LocalCloud readiness summary", "application/json"));
        resources.add(resource("localcloud://compatibility", "Compatibility registry", "Canonical LocalCloud compatibility registry", "application/json"));
        resources.add(resource("localcloud://diagnostics/latest", "Diagnostics", "Current diagnostics bundle", "application/json"));
        resources.add(resource("localcloud://terraform/readiness", "Terraform readiness", "Terraform-oriented LocalCloud readiness and endpoint checks", "application/json"));
        return resources;
    }

    private List<Map<String, Object>> resourceTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();
        templates.add(resourceTemplate("localcloud://readiness/{service}", "Service readiness", "Readiness for one LocalCloud service", "application/json"));
        templates.add(resourceTemplate("localcloud://compatibility/{service}", "Service compatibility", "Compatibility status for one LocalCloud service", "application/json"));
        templates.add(resourceTemplate("localcloud://browse/{service}/{resourceType}", "Browse resource type", "List local resources for a service resource type", "application/json"));
        templates.add(resourceTemplate("localcloud://browse/{service}/{resourceType}/{resourceId}", "Browse resource", "Read one local resource or resource collection", "application/json"));
        return templates;
    }

    private Map<String, Object> readResource(Map<String, Object> params) throws Exception {
        String uri = requiredString(params, "uri");
        ResourceContent content = resourceContent(uri);
        return Map.of("contents", List.of(content.toMap()));
    }

    private ResourceContent resourceContent(String uri) throws Exception {
        URI parsed = URI.create(uri);
        if (!"localcloud".equals(parsed.getScheme())) {
            throw new McpException(-32602, "Invalid params", "Unsupported resource URI scheme: " + parsed.getScheme());
        }
        String host = parsed.getHost();
        List<String> segments = pathSegments(parsed.getPath());
        if ("services".equals(host)) {
            return jsonContent(uri, servicesPayload());
        }
        if ("env".equals(host) && !segments.isEmpty()) {
            String format = segments.get(0);
            MediaText mediaText = envContent(format, null);
            return new ResourceContent(uri, mediaText.mimeType(), mediaText.text());
        }
        if ("readiness".equals(host)) {
            Object value = segments.isEmpty() ? readinessPayload(null) : readinessPayload(segments.get(0));
            return jsonContent(uri, value);
        }
        if ("compatibility".equals(host)) {
            Object value = segments.isEmpty() ? CapabilityCatalog.compatibility(config) : serviceCompatibility(segments.get(0));
            return jsonContent(uri, value);
        }
        if ("diagnostics".equals(host) && segments.size() == 1 && "latest".equals(segments.get(0))) {
            return new ResourceContent(uri, "application/json", responseText(diagnosticsService.diagnostics(null)));
        }
        if ("terraform".equals(host) && segments.size() == 1 && "readiness".equals(segments.get(0))) {
            return jsonContent(uri, terraformReadinessPayload());
        }
        if ("browse".equals(host) && !segments.isEmpty()) {
            String service = segments.get(0);
            String resourceType = segments.size() > 1 ? segments.get(1) : null;
            String resourceId = segments.size() > 2 ? String.join("/", segments.subList(2, segments.size())) : null;
            return new ResourceContent(uri, "application/json",
                    responseText(browseService.browseService(service, resourceType, resourceId, config.getProjectId())));
        }
        throw new McpException(-32602, "Invalid params", "Unknown resource URI: " + uri);
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        addTool(tools, "localcloud_list_services", "List LocalCloud services with endpoint, env var, protocol, status, and compatibility metadata", schema(), true, false);
        addTool(tools, "localcloud_get_service", "Get one LocalCloud service by id", schema(props(prop("service", "string", "Service id such as gcs or bigquery")), required("service")), true, false);
        addTool(tools, "localcloud_get_env", "Generate SDK, gcloud, OAuth, shell, JSON, or Terraform environment configuration", schema(props(
                prop("format", "string", "shell, json, terraform, oauth, or docker-compose"),
                prop("project", "string", "Optional project id override")), List.of()), true, false);
        addTool(tools, "localcloud_check_readiness", "Check global readiness or readiness for one service", schema(prop("service", "string", "Optional service id"), List.of()), true, false);
        addTool(tools, "localcloud_check_compatibility", "Check global or service-specific LocalCloud compatibility", schema(props(
                prop("service", "string", "Optional service id"),
                prop("surface", "string", "Optional surface filter such as api or terraform")), List.of()), true, false);
        addTool(tools, "localcloud_browse_resources", "Browse local resource inventory for a LocalCloud service", schema(props(
                prop("service", "string", "Service id"),
                prop("resourceType", "string", "Optional resource type"),
                prop("resourceId", "string", "Optional resource id"),
                prop("project", "string", "Optional project id")), required("service")), true, false);
        addTool(tools, "localcloud_read_resource", "Read any localcloud:// MCP resource URI", schema(props(prop("uri", "string", "Resource URI")), required("uri")), true, false);
        addTool(tools, "localcloud_query_data", "Run a local-only SQL/pseudo-SQL query through LocalCloud query APIs", schema(props(
                prop("service", "string", "Service id"),
                prop("sql", "string", "Query text"),
                prop("instance", "string", "Spanner instance, when required"),
                prop("database", "string", "Spanner database, when required")), required("service", "sql")), true, false);
        addTool(tools, "localcloud_generate_sdk_env", "Generate SDK/gcloud environment exports", schema(), true, false);
        addTool(tools, "localcloud_generate_gcloud_env", "Generate gcloud endpoint override exports", schema(), true, false);
        addTool(tools, "localcloud_generate_terraform_env", "Generate Terraform Google provider custom endpoint exports", schema(), true, false);
        addTool(tools, "localcloud_validate_agent_config", "Validate that agent-provided env/config points at LocalCloud and not real Google Cloud", schema(props(
                prop("text", "string", "Optional config text to inspect"),
                prop("env", "object", "Optional env var map to inspect")), List.of()), true, false);
        addTool(tools, "localcloud_get_diagnostics", "Get the current LocalCloud diagnostics bundle", schema(), true, false);
        addTool(tools, "localcloud_get_recent_requests", "Get recent LocalCloud request log entries", schema(), true, false);
        addTool(tools, "localcloud_get_logs", "Get LocalCloud log-oriented diagnostics available through the request log", schema(), true, false);
        addTool(tools, "localcloud_export_state", "Export LocalCloud state as seed-compatible YAML", schema(props(
                prop("services", "array", "Optional service ids to export"),
                prop("project", "string", "Optional project id")), List.of()), true, false);

        if (writeEnabled) {
            addTool(tools, "localcloud_seed_project", "Seed LocalCloud project data from seed YAML", schema(props(
                    prop("yaml", "string", "Seed YAML content"),
                    prop("volatileOnly", "boolean", "Skip persistent services when true")), required("yaml")), false, false);
            addTool(tools, "localcloud_import_state", "Import LocalCloud state. Currently reserved for future import-state wiring", schema(props(prop("yaml", "string", "State YAML content")), required("yaml")), false, false);
        }
        if (destructiveEnabled) {
            addTool(tools, "localcloud_reset_project", "Reset all LocalCloud data for a project", schema(prop("project", "string", "Optional project id"), List.of()), false, true);
            addTool(tools, "localcloud_reset_service", "Reset one LocalCloud service", schema(props(
                    prop("service", "string", "Service id"),
                    prop("restore_seed", "boolean", "Restore last seed for that service")), required("service")), false, true);
            addTool(tools, "localcloud_create_fault", "Create a local fault injection rule", schema(prop("fault", "object", "Fault rule object"), required("fault")), false, true);
            addTool(tools, "localcloud_clear_faults", "Clear all local fault injection rules", schema(), false, true);
        }
        return tools;
    }

    private Map<String, Object> callTool(ServiceRequestContext ctx, Map<String, Object> params) throws Exception {
        String name = requiredString(params, "name");
        Map<String, Object> args = asMap(params.get("arguments"));
        if (WRITE_TOOLS.contains(name) && !writeEnabled) {
            return toolError("Tool " + name + " requires LOCALCLOUD_MCP_WRITE=true");
        }
        if (DESTRUCTIVE_TOOLS.contains(name) && !destructiveEnabled) {
            return toolError("Tool " + name + " requires LOCALCLOUD_MCP_DESTRUCTIVE=true");
        }
        Object payload = switch (name) {
            case "localcloud_list_services" -> servicesPayload();
            case "localcloud_get_service" -> getServicePayload(requiredString(args, "service"));
            case "localcloud_get_env" -> {
                String format = string(args.getOrDefault("format", "shell"));
                String project = string(args.get("project"));
                MediaText text = envContent(format, project);
                yield text.text();
            }
            case "localcloud_check_readiness" -> readinessPayload(string(args.get("service")));
            case "localcloud_check_compatibility" -> {
                String service = string(args.get("service"));
                if (service == null || service.isBlank()) {
                    yield CapabilityCatalog.compatibility(config);
                }
                Map<String, Object> result = serviceCompatibility(service);
                String surface = string(args.get("surface"));
                if (surface != null && !surface.isBlank()) {
                    result.put("warnings", CapabilityCatalog.warnings(config, service, surface));
                }
                yield result;
            }
            case "localcloud_browse_resources" -> browsePayload(args);
            case "localcloud_read_resource" -> resourceContent(requiredString(args, "uri")).text();
            case "localcloud_query_data" -> responseText(queryService.query(ctx, AggregatedHttpRequest.of(
                    HttpMethod.POST, "/query", MediaType.JSON, mapper.writeValueAsString(args))));
            case "localcloud_generate_sdk_env", "localcloud_generate_gcloud_env" -> envContent("shell", null).text();
            case "localcloud_generate_terraform_env" -> envContent("terraform", null).text();
            case "localcloud_validate_agent_config" -> validateAgentConfig(args);
            case "localcloud_get_diagnostics" -> responseText(diagnosticsService.diagnostics(ctx));
            case "localcloud_get_recent_requests", "localcloud_get_logs" -> responseText(diagnosticsService.requests(ctx));
            case "localcloud_export_state" -> exportState(args);
            case "localcloud_seed_project" -> seedService.seedYaml(requiredString(args, "yaml"), booleanArg(args, "volatileOnly"));
            case "localcloud_reset_project" -> resetProject(args);
            case "localcloud_reset_service" -> responseText(seedService.resetService(ctx, requiredString(args, "service"), AggregatedHttpRequest.of(
                    HttpMethod.POST, "/reset/" + requiredString(args, "service"), MediaType.JSON,
                    mapper.writeValueAsString(Map.of("restore_seed", booleanArg(args, "restore_seed"))))));
            case "localcloud_import_state" -> throw new McpException(-32601, "Tool not implemented", "localcloud_import_state is reserved for future import-state wiring");
            case "localcloud_create_fault" -> responseText(faultInjectionService.createFault(AggregatedHttpRequest.of(
                    HttpMethod.POST, "/faults", MediaType.JSON, mapper.writeValueAsString(args.get("fault")))));
            case "localcloud_clear_faults" -> responseText(faultInjectionService.clearFaults());
            default -> throw new McpException(-32601, "Tool not found", name);
        };
        return toolResult(payload);
    }

    private Map<String, Object> servicesPayload() {
        List<Map<String, Object>> services = new ArrayList<>();
        for (Map.Entry<String, ServiceDefinition> entry : config.getServiceRegistry().getAllServices().entrySet()) {
            services.add(serviceRow(entry.getKey(), entry.getValue()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("services", services);
        response.put("safety", safetyState());
        response.put("real_google_cloud_fallback", false);
        return response;
    }

    private Map<String, Object> getServicePayload(String service) {
        ServiceDefinition def = config.getServiceRegistry().getService(service);
        if (def == null) {
            throw new McpException(-32602, "Invalid params", "Unknown LocalCloud service: " + service);
        }
        return serviceRow(service, def);
    }

    private Map<String, Object> serviceRow(String service, ServiceDefinition def) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", service);
        row.put("name", def.displayName());
        row.put("enabled", config.isServiceDynamicallyEnabled(service));
        row.put("enabled_source", config.getConfigSource(service));
        row.put("port", def.port());
        row.put("protocol", def.protocol());
        row.put("endpoint", def.envValue("localhost"));
        row.put("env_var", def.envVar());
        row.put("env_value", def.envValue("localhost"));
        row.put("type", def.type());
        row.put("default_enabled", def.defaultEnabled());
        row.put("gcloud_api_name", def.gcloudApiName());
        row.put("gcloud_env_var", def.gcloudEnvVar());
        row.put("terraform_env_var", def.terraformEnvVar());
        row.put("min_tier", def.minTier() != null ? def.minTier().name().toLowerCase(Locale.ROOT) : "community");
        row.put("compatibility", serviceCompatibility(def.id()));
        return row;
    }

    private Object readinessPayload(String service) throws Exception {
        if (service == null || service.isBlank()) {
            return parseJson(responseText(diagnosticsService.diagnostics(null)));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", service);
        response.put("enabled", config.isServiceEnabled(service));
        response.put("known", config.getServiceRegistry().getService(service) != null);
        response.put("checked_at", Instant.now().toString());
        response.put("remediation", config.isServiceEnabled(service)
                ? "Use local endpoint from localcloud://services or localcloud_get_env."
                : "Enable with LOCALCLOUD_ENABLE_" + service.toUpperCase(Locale.ROOT) + "=true or LOCALCLOUD_SERVICES.");
        response.put("compatibility", serviceCompatibility(service));
        return response;
    }

    private Map<String, Object> terraformReadinessPayload() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ready", true);
        payload.put("endpoint_env", envContent("terraform", null).text());
        payload.put("real_google_cloud_fallback", false);
        payload.put("note", "Use /terraform/readiness for live DNS and endpoint checks when the HTTP server is running.");
        return payload;
    }

    private Object browsePayload(Map<String, Object> args) throws Exception {
        String service = requiredString(args, "service");
        String resourceType = string(args.get("resourceType"));
        String resourceId = string(args.get("resourceId"));
        String project = string(args.getOrDefault("project", config.getProjectId()));
        return parseJson(responseText(browseService.browseService(service, resourceType, resourceId, project)));
    }

    private Object exportState(Map<String, Object> args) throws Exception {
        Set<String> selected = null;
        Object services = args.get("services");
        if (services instanceof List<?> list && !list.isEmpty()) {
            selected = list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
        return exportService.exportYaml(selected, string(args.get("project")));
    }

    private Map<String, Object> resetProject(Map<String, Object> args) {
        String project = string(args.getOrDefault("project", config.getProjectId()));
        int deleted = seedService.resetProjectData(project);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("project", project);
        response.put("deleted_count", deleted);
        return response;
    }

    private Map<String, Object> validateAgentConfig(Map<String, Object> args) throws Exception {
        StringBuilder inspected = new StringBuilder();
        Object text = args.get("text");
        if (text != null) {
            inspected.append(text).append('\n');
        }
        Object env = args.get("env");
        if (env != null) {
            inspected.append(mapper.writeValueAsString(env));
        }
        String value = inspected.toString();
        boolean referencesRealGoogle = value.contains("googleapis.com") && !value.contains("localhost") && !value.contains("127.0.0.1");
        boolean referencesLocalhost = value.contains("localhost") || value.contains("127.0.0.1");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", !referencesRealGoogle);
        result.put("references_localcloud", referencesLocalhost);
        result.put("references_real_google_cloud", referencesRealGoogle);
        result.put("recommendation", referencesRealGoogle
                ? "Replace googleapis.com endpoints with LocalCloud env exports from localcloud_get_env."
                : "Configuration does not show real Google Cloud endpoint fallback.");
        result.put("env", parseJson(envContent("json", null).text()));
        return result;
    }

    private MediaText envContent(String requestedFormat, String projectOverride) throws Exception {
        String format = requestedFormat == null || requestedFormat.isBlank() ? "shell" : requestedFormat;
        Map<String, String> envVars = envVars(projectOverride);
        if ("json".equals(format)) {
            return new MediaText("application/json", mapper.writeValueAsString(envVars));
        }
        if ("oauth".equals(format)) {
            Map<String, Object> oauth = new LinkedHashMap<>();
            oauth.put("token_uri", "http://localhost:" + config.getGatewayPort() + "/oauth2/token");
            oauth.put("auth_uri", "http://localhost:" + config.getGatewayPort() + "/oauth2/auth");
            oauth.put("access_token", "ya29.localcloud-dev-access-token");
            oauth.put("token_type", "Bearer");
            oauth.put("expires_in", 3600);
            return new MediaText("application/json", mapper.writeValueAsString(oauth));
        }
        if ("docker-compose".equals(format)) {
            StringBuilder sb = new StringBuilder("# docker-compose environment variables\nenvironment:\n");
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": \"").append(entry.getValue()).append("\"\n");
            }
            return new MediaText("text/plain", sb.toString());
        }
        if ("terraform".equals(format)) {
            return new MediaText("text/plain", terraformEnv(projectOverride));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            sb.append("export ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"\n");
        }
        return new MediaText("text/plain", sb.toString());
    }

    private Map<String, String> envVars(String projectOverride) {
        Map<String, String> envVars = new LinkedHashMap<>();
        ServiceRegistry registry = config.getServiceRegistry();
        for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
            String service = entry.getKey();
            if (config.isServiceEnabled(service)) {
                ServiceDefinition def = entry.getValue();
                envVars.put(def.envVar(), def.envValue("localhost"));
            }
        }
        String projectId = projectOverride != null && !projectOverride.isBlank() ? projectOverride : config.getProjectId();
        envVars.put("GOOGLE_CLOUD_PROJECT", projectId);
        envVars.put("GCLOUD_PROJECT", projectId);
        for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
            String service = entry.getKey();
            if (config.isServiceEnabled(service)) {
                String gcloudVar = entry.getValue().gcloudEnvVar();
                if (gcloudVar != null) {
                    envVars.put(gcloudVar, entry.getValue().gcloudEndpoint("localhost"));
                }
            }
        }
        envVars.put("CLOUDSDK_CORE_PROJECT", projectId);
        envVars.put("CLOUDSDK_AUTH_ACCESS_TOKEN", "localcloud-dev-token");
        return envVars;
    }

    private String terraformEnv(String projectOverride) {
        String projectId = projectOverride != null && !projectOverride.isBlank() ? projectOverride : config.getProjectId();
        StringBuilder sb = new StringBuilder();
        sb.append("# LocalCloud Terraform environment — run:\n");
        sb.append("# eval $(curl -s http://localhost:").append(config.getGatewayPort()).append("/env?format=terraform)\n\n");
        for (Map.Entry<String, ServiceDefinition> entry : config.getServiceRegistry().getAllServices().entrySet()) {
            String service = entry.getKey();
            if (!config.isServiceEnabled(service)) {
                continue;
            }
            ServiceDefinition def = entry.getValue();
            String tfVar = def.terraformEnvVar();
            if (tfVar == null || tfVar.isBlank()) {
                continue;
            }
            String endpoint = terraformEndpoint(service, def);
            sb.append("export ").append(tfVar).append("=\"").append(endpoint).append("\"\n");
        }
        sb.append("export GOOGLE_PROJECT=\"").append(projectId).append("\"\n");
        sb.append("export GOOGLE_OAUTH_ACCESS_TOKEN=\"ya29.localcloud-dev-access-token\"\n");
        sb.append("export GOOGLE_OAUTH_CUSTOM_ENDPOINT=\"http://localhost:").append(config.getGatewayPort()).append("/oauth2/\"\n");
        sb.append("export GOOGLE_OPENID_CONNECT_CUSTOM_ENDPOINT=\"http://localhost:").append(config.getGatewayPort()).append("/oauth2/\"\n");
        sb.append("export GOOGLE_APPLICATION_CREDENTIALS=\"/dev/null\"\n");
        return sb.toString();
    }

    private String terraformEndpoint(String service, ServiceDefinition def) {
        String endpoint;
        if ("spanner".equals(service) && def.additionalPorts() != null && def.additionalPorts().containsKey("rest")) {
            endpoint = "http://localhost:" + def.additionalPorts().get("rest") + "/v1";
        } else if ("memorystore".equals(service)) {
            endpoint = "http://localhost:" + config.getGatewayPort() + "/redis/v1";
        } else if ("bigtable".equals(service) || "pubsub".equals(service)) {
            endpoint = "http://localhost:" + config.getGatewayPort();
        } else {
            endpoint = def.envValue("localhost");
            if (!endpoint.startsWith("http")) {
                endpoint = "http://" + endpoint;
            }
        }
        if ("gcs".equals(service)) {
            endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            endpoint = endpoint + "/storage/v1";
        }
        if (def.isFacade()) {
            String prefix = switch (service) {
                case "cloudtasks" -> "/v2/";
                case "cloudresourcemanager", "cloudbilling", "cloudsql", "dataproc", "bigtable", "logging", "monitoring" -> "";
                default -> "/v1/";
            };
            endpoint = endpoint + prefix;
        }
        return endpoint.endsWith("/") ? endpoint : endpoint + "/";
    }

    private Map<String, Object> serviceCompatibility(String serviceId) {
        Map<String, Object> compatibility = CapabilityCatalog.compatibility(config);
        Object services = compatibility.get("services");
        if (services instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> serviceMap && serviceId.equals(String.valueOf(serviceMap.get("id")))) {
                    return new LinkedHashMap<>(asMap(serviceMap));
                }
            }
        }
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("id", serviceId);
        unknown.put("status", "unknown");
        unknown.put("warnings", List.of("No compatibility registry entry found for service"));
        return unknown;
    }

    private List<Map<String, Object>> prompts() {
        List<Map<String, Object>> prompts = new ArrayList<>();
        prompts.add(prompt("use-localcloud-instead-of-gcp", "Configure an agent to use LocalCloud endpoints instead of real Google Cloud"));
        prompts.add(prompt("debug-localcloud-service", "Gather readiness, diagnostics, recent requests, compatibility, and remediation for a service"));
        prompts.add(prompt("write-localcloud-integration-test", "Write a local-only integration test against LocalCloud endpoints"));
        prompts.add(prompt("seed-localcloud-scenario", "Generate seed YAML for a requested LocalCloud scenario"));
        prompts.add(prompt("terraform-with-localcloud", "Prepare Terraform Google provider custom endpoint env and readiness checks"));
        prompts.add(prompt("compatibility-aware-implementation", "Check LocalCloud compatibility before choosing Google Cloud APIs"));
        return prompts;
    }

    private Map<String, Object> getPrompt(Map<String, Object> params) {
        String name = requiredString(params, "name");
        String text = switch (name) {
            case "use-localcloud-instead-of-gcp" -> "Before running cloud code, call localcloud_get_env and configure SDKs, gcloud, or Terraform with LocalCloud localhost endpoints. Do not call real googleapis.com endpoints.";
            case "debug-localcloud-service" -> "Debug LocalCloud service '${service}' by checking localcloud_check_readiness, localcloud_check_compatibility, localcloud_get_diagnostics, and localcloud_get_recent_requests. Return concrete remediation steps.";
            case "write-localcloud-integration-test" -> "Write a local-only integration test for '${service}' using LocalCloud env vars. Verify requests hit localhost and include compatibility caveats for unsupported APIs.";
            case "seed-localcloud-scenario" -> "Create seed YAML for this scenario: ${scenario}. Prefer services: wrapper format and keep resource ids deterministic.";
            case "terraform-with-localcloud" -> "Call localcloud_generate_terraform_env and localcloud_check_readiness before terraform plan/apply. Use GOOGLE_*_CUSTOM_ENDPOINT values and never rely on real Google Cloud fallback.";
            case "compatibility-aware-implementation" -> "Call localcloud_check_compatibility for the target service and API surface before selecting operations. If unsupported, return an explicit LocalCloud compatibility limitation.";
            default -> throw new McpException(-32602, "Invalid params", "Unknown prompt: " + name);
        };
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", "LocalCloud prompt: " + name);
        result.put("messages", List.of(message));
        return result;
    }

    private HttpResponse json(HttpStatus status, Object body) {
        try {
            return HttpResponse.of(status, MediaType.JSON, mapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                    "{\"error\":{\"message\":\"Failed to serialize MCP response\"}}");
        }
    }

    private HttpResponse forbidden() {
        return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.JSON,
                "{\"error\":\"LocalCloud MCP rejects non-local Origin or remote requests by default\"}");
    }

    private boolean isAllowedRequest(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        if (remoteAllowed) {
            return true;
        }
        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            try {
                String host = URI.create(origin).getHost();
                if (!isLoopbackHost(host)) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        if (ctx != null && ctx.remoteAddress() instanceof InetSocketAddress remote) {
            InetAddress address = remote.getAddress();
            return address == null || address.isLoopbackAddress() || address.isAnyLocalAddress();
        }
        return true;
    }

    private boolean isLoopbackHost(String host) {
        return host == null
                || "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private Object parseJson(String text) throws Exception {
        return mapper.readValue(text, Object.class);
    }

    private String responseText(HttpResponse response) {
        return response.aggregate().join().contentUtf8();
    }

    private ResourceContent jsonContent(String uri, Object value) throws Exception {
        return new ResourceContent(uri, "application/json", mapper.writeValueAsString(value));
    }

    private Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    private Map<String, Object> toolResult(Object payload) throws Exception {
        String text = payload instanceof String value ? value : mapper.writeValueAsString(payload);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("isError", false);
        return result;
    }

    private Map<String, Object> toolError(String message) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("isError", true);
        return result;
    }

    private List<String> pathSegments(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return List.of();
        }
        String raw = path.startsWith("/") ? path.substring(1) : path;
        List<String> segments = new ArrayList<>();
        for (String segment : raw.split("/")) {
            if (!segment.isBlank()) {
                segments.add(URLDecoder.decode(segment, StandardCharsets.UTF_8));
            }
        }
        return segments;
    }

    private Map<String, Object> params(Map<String, Object> request) {
        return asMap(request.get("params"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            raw.forEach((k, v) -> mapped.put(String.valueOf(k), v));
            return mapped;
        }
        return new LinkedHashMap<>();
    }

    private String requiredString(Map<String, Object> map, String key) {
        String value = string(map.get(key));
        if (value == null || value.isBlank()) {
            throw new McpException(-32602, "Invalid params", "Missing required parameter: " + key);
        }
        return value;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanArg(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private Map<String, Object> resource(String uri, String name, String description, String mimeType) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uri", uri);
        resource.put("name", name);
        resource.put("description", description);
        resource.put("mimeType", mimeType);
        return resource;
    }

    private Map<String, Object> resourceTemplate(String uriTemplate, String name, String description, String mimeType) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("uriTemplate", uriTemplate);
        template.put("name", name);
        template.put("description", description);
        template.put("mimeType", mimeType);
        return template;
    }

    private Map<String, Object> prompt(String name, String description) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", name);
        prompt.put("description", description);
        return prompt;
    }

    private void addTool(List<Map<String, Object>> tools, String name, String description,
                         Map<String, Object> inputSchema, boolean readOnly, boolean destructive) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", destructive);
        annotations.put("openWorldHint", false);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        tool.put("annotations", annotations);
        tools.add(tool);
    }

    private Map<String, Object> schema() {
        return schema(Map.of(), List.of());
    }

    private Map<String, Object> schema(List<Map<String, Object>> properties, List<String> required) {
        return schema(props(properties), required);
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> props(Map<String, Object>... properties) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> property : properties) {
            out.put(String.valueOf(property.get("name")), property);
        }
        return out;
    }

    private Map<String, Object> props(List<Map<String, Object>> properties) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> property : properties) {
            out.put(String.valueOf(property.get("name")), property);
        }
        return out;
    }

    private Map<String, Object> prop(String name, String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("name", name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private List<String> required(String... names) {
        return List.of(names);
    }

    private record ResourceContent(String uri, String mimeType, String text) {
        Map<String, Object> toMap() {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("uri", uri);
            content.put("mimeType", mimeType);
            content.put("text", text);
            return content;
        }
    }

    private record MediaText(String mimeType, String text) {}

    private static class McpException extends RuntimeException {
        private final int code;
        private final String mcpMessage;

        McpException(int code, String mcpMessage, String detail) {
            super(detail);
            this.code = code;
            this.mcpMessage = mcpMessage;
        }
    }
}
