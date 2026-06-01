package com.localcloud.admin;

import static com.localcloud.admin.AdminApiSupport.errorResponse;
import static com.localcloud.admin.AdminApiSupport.mapper;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Environment variable generation, OAuth2 token stubs, and service profiles.
 * Extracted from AdminApiService.
 */
public class EnvService {

    private static final Logger logger = LoggerFactory.getLogger(EnvService.class);
    private final LocalCloudConfig config;

    public EnvService(LocalCloudConfig config) {
        this.config = config;
    }

    @Get("/env")
    public HttpResponse env(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            String format = params.get("format", "shell");

            Map<String, String> envVars = new LinkedHashMap<>();
            ServiceRegistry registry = config.getServiceRegistry();
            for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                String service = entry.getKey();
                if (config.isServiceEnabled(service)) {
                    ServiceDefinition def = entry.getValue();
                    envVars.put(def.envVar(), def.envValue("localhost"));
                }
            }

            String projectParam = params.get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();
            envVars.put("GOOGLE_CLOUD_PROJECT", projectId);
            envVars.put("GCLOUD_PROJECT", projectId);

            for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                String service = entry.getKey();
                if (config.isServiceEnabled(service)) {
                    ServiceDefinition def = entry.getValue();
                    String gcloudVar = def.gcloudEnvVar();
                    if (gcloudVar != null) {
                        envVars.put(gcloudVar, def.gcloudEndpoint("localhost"));
                    }
                }
            }
            envVars.put("CLOUDSDK_CORE_PROJECT", projectId);
            envVars.put("CLOUDSDK_AUTH_ACCESS_TOKEN", "localcloud-dev-token");

            return switch (format) {
                case "json" -> {
                    String json = mapper().writerWithDefaultPrettyPrinter().writeValueAsString(envVars);
                    yield HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
                }
                case "oauth" -> {
                    String json = mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "token_uri", "http://localhost:8080/oauth2/token",
                        "auth_uri", "http://localhost:8080/oauth2/auth",
                        "access_token", "ya29.localcloud-dev-access-token",
                        "token_type", "Bearer",
                        "expires_in", 3600
                    ));
                    yield HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
                }
                case "docker-compose" -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# docker-compose environment variables\n");
                    sb.append("environment:\n");
                    for (Map.Entry<String, String> e : envVars.entrySet()) {
                        sb.append("  ").append(e.getKey()).append(": \"")
                          .append(e.getValue()).append("\"\n");
                    }
                    yield HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, sb.toString());
                }
                case "terraform" -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# LocalCloud Terraform environment — run:\n");
                    sb.append("# eval $(curl -s http://localhost:8080/env?format=terraform)\n\n");
                    for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                        String service = entry.getKey();
                        if (!config.isServiceEnabled(service)) continue;
                        ServiceDefinition def = entry.getValue();
                        String tfVar = def.terraformEnvVar();
                        if (tfVar == null || tfVar.isEmpty()) continue;
                        String endpoint;
                        if ("spanner".equals(service) && def.additionalPorts().containsKey("rest")) {
                            endpoint = "http://localhost:" + def.additionalPorts().get("rest") + "/v1";
                        } else if ("memorystore".equals(service)) {
                            endpoint = "http://localhost:" + config.getGatewayPort() + "/redis/v1";
                        } else if ("bigtable".equals(service)) {
                            endpoint = "http://localhost:" + config.getGatewayPort();
                        } else if ("pubsub".equals(service)) {
                            endpoint = "http://localhost:" + config.getGatewayPort();
                        } else {
                            endpoint = def.envValue("localhost");
                            if (!endpoint.startsWith("http")) endpoint = "http://" + endpoint;
                        }
                        if ("gcs".equals(service)) {
                            if (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
                            endpoint = endpoint + "/storage/v1";
                        }
                        if (def.isFacade()) {
                            String versionPrefix = switch (service) {
                                case "cloudtasks" -> "/v2/";
                                case "cloudresourcemanager", "cloudbilling", "cloudsql", "dataproc", "bigtable", "logging", "monitoring" -> "";
                                default -> "/v1/";
                            };
                            if (!versionPrefix.isEmpty()) endpoint = endpoint + versionPrefix;
                        }
                        if (!endpoint.endsWith("/")) endpoint += "/";
                        sb.append("export ").append(tfVar).append("=\"").append(endpoint).append("\"\n");
                    }
                    sb.append("export GOOGLE_PROJECT=\"").append(projectId).append("\"\n");
                    sb.append("export GOOGLE_OAUTH_ACCESS_TOKEN=\"ya29.localcloud-dev-access-token\"\n");
                    sb.append("export GOOGLE_OAUTH_CUSTOM_ENDPOINT=\"http://localhost:8080/oauth2/\"\n");
                    sb.append("export GOOGLE_OPENID_CONNECT_CUSTOM_ENDPOINT=\"http://localhost:8080/oauth2/\"\n");
                    sb.append("export BIGTABLE_EMULATOR_HOST=\"localhost:8087\"\n");
                    sb.append("export GOOGLE_APPLICATION_CREDENTIALS=\"/dev/null\"\n");
                    sb.append("\n# REQUIRED: DNS redirect for all *.googleapis.com (one-time setup)\n");
                    sb.append("# sudo sh -c 'echo \"nameserver 127.0.0.1\" > /etc/resolver/googleapis.com'\n");
                    sb.append("# REQUIRED: Docker must map port 443: -p 443:8080\n");
                    sb.append("# Verify readiness: curl http://localhost:").append(config.getGatewayPort()).append("/terraform/readiness\n");
                    yield HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, sb.toString());
                }
                default -> {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> e : envVars.entrySet()) {
                        sb.append("export ").append(e.getKey()).append("=\"")
                          .append(e.getValue()).append("\"\n");
                    }
                    yield HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, sb.toString());
                }
            };
        } catch (Exception e) {
            logger.error("Error generating env output", e);
            return errorResponse(e);
        }
    }

    @Post("/oauth2/token")
    public HttpResponse oauth2Token(AggregatedHttpRequest req) {
        try {
            String json = mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "access_token", "ya29.localcloud-" + System.currentTimeMillis(),
                "token_type", "Bearer",
                "expires_in", 3600,
                "scope", "https://www.googleapis.com/auth/cloud-platform"
            ));
            logger.info("OAuth2 token request from client");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error generating OAuth2 token", e);
            return errorResponse(e);
        }
    }

    @Get("/oauth2/auth")
    public HttpResponse oauth2Auth(ServiceRequestContext ctx) {
        String redirectUri = ctx.queryParams().get("redirect_uri", "http://localhost");
        String state = ctx.queryParams().get("state", "");
        String location = redirectUri + "?code=localcloud-auth-code&state=" + state;
        return HttpResponse.builder()
            .status(HttpStatus.TEMPORARY_REDIRECT)
            .header("Location", location)
            .content(MediaType.PLAIN_TEXT, "Redirecting to " + location)
            .build();
    }

    @Get("/profiles")
    public HttpResponse profiles() {
        try {
            String json = mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(CapabilityCatalog.profiles(config));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error generating profile catalog", e);
            return errorResponse(e);
        }
    }
}
