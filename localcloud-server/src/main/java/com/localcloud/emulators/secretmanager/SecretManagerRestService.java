package com.localcloud.emulators.secretmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final ObjectMapper mapper = new ObjectMapper();

    public SecretManagerRestService(SecretManagerStore store, SecretManagerEmulator emulator) {
        this.store = store;
        this.emulator = emulator;
    }

    @Post("/projects/{project}/secrets")
    public HttpResponse createSecret(ServiceRequestContext ctx, @Param String project, String body) {
        emulator.incrementRequestCount();
        try {
            String secretId = ctx.queryParams().get("secretId");
            var parsed = mapper.readTree(body);
            if (secretId == null || secretId.isBlank()) {
                if (parsed.has("secretId")) {
                    secretId = parsed.get("secretId").asText();
                }
            }
            if (secretId == null || secretId.isBlank()) {
                return errorResponse(400, "Missing required parameter: secretId");
            }

            String labels = "{}";
            if (parsed.has("labels")) {
                labels = mapper.writeValueAsString(parsed.get("labels"));
            }

            store.createSecret(project, secretId, labels);

            ObjectNode result = mapper.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secretId);
            result.put("createTime", java.time.Instant.now().toString());
            result.set("replication", mapper.createObjectNode().set("automatic", mapper.createObjectNode()));

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                return errorResponse(409, "Secret already exists: " + e.getMessage());
            }
            logger.error("Error creating secret", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/secrets/{secretId}")
    public HttpResponse getSecret(@Param String project, @Param String secretId) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> secret = store.getSecret(project, secretId);
            if (secret == null) {
                return errorResponse(404, "Secret not found: " + secretId);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("name", "projects/" + project + "/secrets/" + secretId);
            result.put("createTime", String.valueOf(secret.get("created_at")));
            result.set("replication", mapper.createObjectNode().set("automatic", mapper.createObjectNode()));
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting secret", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/secrets")
    public HttpResponse listSecrets(@Param String project) {
        emulator.incrementRequestCount();
        try {
            List<Map<String, Object>> secrets = store.listSecrets(project);
            ArrayNode secretsArray = mapper.createArrayNode();
            for (Map<String, Object> s : secrets) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", "projects/" + project + "/secrets/" + s.get("secret_id"));
                node.put("createTime", String.valueOf(s.get("created_at")));
                node.set("replication", mapper.createObjectNode().set("automatic", mapper.createObjectNode()));
                secretsArray.add(node);
            }
            ObjectNode result = mapper.createObjectNode();
            result.set("secrets", secretsArray);
            result.put("totalSize", secrets.size());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error listing secrets", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/secrets/{secretId}")
    public HttpResponse deleteSecret(@Param String project, @Param String secretId) {
        emulator.incrementRequestCount();
        try {
            boolean deleted = store.deleteSecret(project, secretId);
            if (!deleted) {
                return errorResponse(404, "Secret not found: " + secretId);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}");
        } catch (Exception e) {
            logger.error("Error deleting secret", e);
            return errorResponse(500, e.getMessage());
        }
    }

    private HttpResponse errorResponse(int code, String message) {
        try {
            ObjectNode error = mapper.createObjectNode();
            ObjectNode inner = mapper.createObjectNode();
            inner.put("code", code);
            inner.put("message", message);
            error.set("error", inner);
            return HttpResponse.of(HttpStatus.valueOf(code), MediaType.JSON, mapper.writeValueAsString(error));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
