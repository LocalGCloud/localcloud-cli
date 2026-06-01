package com.localcloud.emulators.secretmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.common.RestResponseHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.localcloud.emulators.iam.IAMPolicyRestHandler;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for Secret Manager matching the Google Cloud API surface.
 * Terraform's google_secret_manager_secret resource calls these paths.
 *
 * <p>Handles secret-level CRUD: create, get, list, delete.
 * Version operations (addVersion, access) are served by gRPC HTTP/JSON
 * transcoding via the SecretManagerEmulator's gRPC service, which is
 * registered on the same gateway with transcoding enabled.
 *
 * Routes: /v1/projects/{project}/secrets[/{secretId}]
 */
public class SecretManagerRestService {

    private static final Logger logger = LoggerFactory.getLogger(SecretManagerRestService.class);

    private final SecretManagerStore store;
    private final SecretManagerEmulator emulator;
    private final IAMPolicyRestHandler iamHandler;

    public SecretManagerRestService(SecretManagerStore store, SecretManagerEmulator emulator) {
        this(store, emulator, null);
    }

    public SecretManagerRestService(SecretManagerStore store, SecretManagerEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.store = store;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
    }

    @Post("/projects/{project}/secrets")
    public HttpResponse createSecret(ServiceRequestContext ctx, @Param String project, String body) {
        emulator.incrementRequestCount();
        try {
            String secretId = ctx.queryParams().get("secretId");
            var parsed = RestResponseHelper.parseBody(body);
            if (secretId == null || secretId.isBlank()) {
                if (parsed.has("secretId")) {
                    secretId = parsed.get("secretId").asText();
                }
            }
            if (secretId == null || secretId.isBlank()) {
                return RestResponseHelper.error(400, "Missing required parameter: secretId");
            }

            String labels = "{}";
            if (parsed.has("labels")) {
                labels = RestResponseHelper.toJson(parsed.get("labels"));
            }

            store.createSecret(project, secretId, labels, "{\"automatic\":{}}", null, null);

            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secretId);
            result.put("createTime", java.time.Instant.now().toString());
            result.set("replication", RestResponseHelper.MAPPER.createObjectNode().set("automatic", RestResponseHelper.MAPPER.createObjectNode()));

            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                return RestResponseHelper.error(409, "Secret already exists: " + e.getMessage());
            }
            logger.error("Error creating secret", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/secrets/{secretId}")
    public HttpResponse getSecret(@Param String project, @Param String secretId) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> secret = store.getSecret(project, secretId);
            if (secret == null) {
                return RestResponseHelper.error(404, "Secret not found: " + secretId);
            }
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secretId);
            result.put("createTime", String.valueOf(secret.get("created_at")));
            result.set("replication", RestResponseHelper.MAPPER.createObjectNode().set("automatic", RestResponseHelper.MAPPER.createObjectNode()));
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error getting secret", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/secrets")
    public HttpResponse listSecrets(@Param String project) {
        emulator.incrementRequestCount();
        try {
            List<Map<String, Object>> secrets = store.listSecrets(project);
            ArrayNode secretsArray = RestResponseHelper.MAPPER.createArrayNode();
            for (Map<String, Object> s : secrets) {
                ObjectNode node = RestResponseHelper.MAPPER.createObjectNode();
                node.put("name", "projects/" + project + "/secrets/" + s.get("secret_id"));
                node.put("createTime", String.valueOf(s.get("created_at")));
                node.set("replication", RestResponseHelper.MAPPER.createObjectNode().set("automatic", RestResponseHelper.MAPPER.createObjectNode()));
                secretsArray.add(node);
            }
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.set("secrets", secretsArray);
            result.put("totalSize", secrets.size());
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error listing secrets", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/secrets/{secretId}")
    public HttpResponse deleteSecret(@Param String project, @Param String secretId) {
        emulator.incrementRequestCount();
        try {
            boolean deleted = store.deleteSecret(project, secretId);
            if (!deleted) {
                return RestResponseHelper.error(404, "Secret not found: " + secretId);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}");
        } catch (Exception e) {
            logger.error("Error deleting secret", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    // IAM Policy endpoints are handled by the generic catch-all in LocalCloudApplication.
    // Service Usage API endpoints are in ServiceUsageRestService (registered at /v1).
    // Cloud Billing endpoints are in CloudBillingRestService (registered at /v1).

    // ── Secret version custom methods (gRPC transcoding doesn't handle query params) ──

    @Post("regex:^/projects/(?<project>[^/]+)/secrets/(?<secret>[^/]+):addVersion$")
    public HttpResponse addVersion(@Param String project, @Param String secret, String body) {
        emulator.incrementRequestCount();
        try {
            var root = RestResponseHelper.parseBody(body);
            String payloadData = root.path("payload").path("data").asText(null);
            if (payloadData == null) {
                return RestResponseHelper.error(400, "Missing required field: payload.data");
            }
            byte[] payload = java.util.Base64.getDecoder().decode(payloadData);
            Map<String, Object> versionRow = store.addSecretVersion(project, secret, payload);
            int versionNum = ((Number) versionRow.get("version_number")).intValue();
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secret + "/versions/" + versionNum);
            result.put("state", String.valueOf(versionRow.getOrDefault("state", "ENABLED")));
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error adding secret version", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Post("regex:^/projects/(?<project>[^/]+)/secrets/(?<secret>[^/]+)/versions/(?<version>[^:]+):destroy$")
    public HttpResponse destroyVersion(@Param String project, @Param String secret,
                                       @Param String version, String body) {
        emulator.incrementRequestCount();
        try {
            int v = Integer.parseInt(version);
            // Idempotent: destroying a non-existent version is a no-op (matches GCP behavior)
            store.destroySecretVersion(project, secret, v);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secret + "/versions/" + version);
            result.put("state", "DESTROYED");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error destroying secret version", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Post("regex:^/projects/(?<project>[^/]+)/secrets/(?<secret>[^/]+)/versions/(?<version>[^:]+):enable$")
    public HttpResponse enableVersion(@Param String project, @Param String secret,
                                      @Param String version, String body) {
        emulator.incrementRequestCount();
        try {
            int v = Integer.parseInt(version);
            // Idempotent: enabling an already-enabled version is a no-op
            store.enableSecretVersion(project, secret, v);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secret + "/versions/" + version);
            result.put("state", "ENABLED");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error enabling secret version", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Post("regex:^/projects/(?<project>[^/]+)/secrets/(?<secret>[^/]+)/versions/(?<version>[^:]+):disable$"
)
    public HttpResponse disableVersion(@Param String project, @Param String secret,
                                       @Param String version, String body) {
        emulator.incrementRequestCount();
        try {
            int v = Integer.parseInt(version);
            // Idempotent: disabling an already-disabled version is a no-op
            store.disableSecretVersion(project, secret, v);
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secret + "/versions/" + version);
            result.put("state", "DISABLED");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error disabling secret version", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }
}
