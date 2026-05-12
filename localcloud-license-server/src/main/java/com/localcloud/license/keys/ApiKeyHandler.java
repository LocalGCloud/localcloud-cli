package com.localcloud.license.keys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.license.auth.SessionAuthDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@ProducesJson
public class ApiKeyHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyHandler.class);
    private final ApiKeyRepository keyRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiKeyHandler(ApiKeyRepository keyRepo) {
        this.keyRepo = keyRepo;
    }

    /** POST /keys/generate — body: {tier} (optional) */
    @Post("/generate")
    public HttpResponse generate(ServiceRequestContext ctx, @RequestObject Map<String, String> body) {
        UUID userId = Objects.requireNonNull(ctx.attr(SessionAuthDecorator.USER_ID_KEY),
            "USER_ID_KEY not set — SessionAuthDecorator missing from route");
        String tier = body.getOrDefault("tier", "community");
        try {
            String rawKey = keyRepo.generateOnlineKey(userId, tier);
            logger.info("Generated {} key for user {}", tier, userId);
            return ok(Map.of("key", rawKey, "tier", tier,
                "message", "Save this key — it will not be shown again"));
        } catch (Exception e) {
            logger.error("Key generation failed: {}", e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Key generation failed");
        }
    }

    /** GET /keys/list */
    @Get("/list")
    public HttpResponse list(ServiceRequestContext ctx) {
        UUID userId = Objects.requireNonNull(ctx.attr(SessionAuthDecorator.USER_ID_KEY),
            "USER_ID_KEY not set — SessionAuthDecorator missing from route");
        try {
            List<ApiKeyRepository.KeyInfo> keys = keyRepo.listUserKeys(userId);
            var result = keys.stream().map(k -> Map.of(
                "id", k.id().toString(),
                "prefix", "lco_" + k.prefix() + "...",
                "tier", k.tier(),
                "mode", k.mode()
            )).toList();
            return ok(result);
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list keys");
        }
    }

    /** POST /keys/revoke — body: {key_id} */
    @Post("/revoke")
    public HttpResponse revoke(ServiceRequestContext ctx, @RequestObject Map<String, String> body) {
        UUID userId = Objects.requireNonNull(ctx.attr(SessionAuthDecorator.USER_ID_KEY),
            "USER_ID_KEY not set — SessionAuthDecorator missing from route");
        String keyId = body.get("key_id");
        if (keyId == null) return error(HttpStatus.BAD_REQUEST, "key_id required");
        try {
            boolean revoked = keyRepo.revokeKey(UUID.fromString(keyId), userId);
            if (!revoked) return error(HttpStatus.NOT_FOUND, "Key not found or already revoked");
            return ok(Map.of("message", "Key revoked"));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Revocation failed");
        }
    }

    private HttpResponse ok(Object body) {
        try { return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, mapper.writeValueAsString(body)); }
        catch (Exception e) { return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private HttpResponse error(HttpStatus status, String message) {
        try { return HttpResponse.of(status, MediaType.JSON_UTF_8, mapper.writeValueAsString(Map.of("error", message))); }
        catch (Exception e) { return HttpResponse.of(status); }
    }
}
