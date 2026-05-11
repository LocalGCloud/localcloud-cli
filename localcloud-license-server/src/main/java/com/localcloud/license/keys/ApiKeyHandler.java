package com.localcloud.license.keys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.license.auth.AuthRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ProducesJson
public class ApiKeyHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyHandler.class);
    private final ApiKeyRepository keyRepo;
    private final AuthRepository authRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiKeyHandler(ApiKeyRepository keyRepo, AuthRepository authRepo) {
        this.keyRepo = keyRepo;
        this.authRepo = authRepo;
    }

    /** POST /keys/generate — body: {email, tier} */
    @Post("/generate")
    public HttpResponse generate(@RequestObject Map<String, String> body) {
        String email = body.get("email");
        String tier = body.getOrDefault("tier", "community");
        if (email == null) return error(HttpStatus.BAD_REQUEST, "email required");
        try {
            UUID userId = authRepo.getUserId(email);
            if (userId == null) return error(HttpStatus.NOT_FOUND, "User not found");
            if (!authRepo.isEmailVerified(email)) return error(HttpStatus.FORBIDDEN, "Email not verified");
            String rawKey = keyRepo.generateOnlineKey(userId, tier);
            logger.info("Generated {} key for {}", tier, email);
            return ok(Map.of("key", rawKey, "tier", tier,
                "message", "Save this key — it will not be shown again"));
        } catch (Exception e) {
            logger.error("Key generation failed: {}", e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Key generation failed");
        }
    }

    /** GET /keys/list?email=x */
    @Get("/list")
    public HttpResponse list(@Param("email") String email) {
        try {
            UUID userId = authRepo.getUserId(email);
            if (userId == null) return error(HttpStatus.NOT_FOUND, "User not found");
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

    /** POST /keys/revoke — body: {email, key_id} */
    @Post("/revoke")
    public HttpResponse revoke(@RequestObject Map<String, String> body) {
        String email = body.get("email");
        String keyId = body.get("key_id");
        if (email == null || keyId == null) return error(HttpStatus.BAD_REQUEST, "email and key_id required");
        try {
            UUID userId = authRepo.getUserId(email);
            if (userId == null) return error(HttpStatus.NOT_FOUND, "User not found");
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
