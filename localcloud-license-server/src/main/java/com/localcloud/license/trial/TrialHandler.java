package com.localcloud.license.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.keys.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

@ProducesJson
public class TrialHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrialHandler.class);
    private final TrialRepository trialRepo;
    private final AuthRepository authRepo;
    private final ApiKeyRepository keyRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public TrialHandler(TrialRepository trialRepo, AuthRepository authRepo, ApiKeyRepository keyRepo) {
        this.trialRepo = trialRepo;
        this.authRepo = authRepo;
        this.keyRepo = keyRepo;
    }

    /** POST /trial/start — body: {email, device_id} */
    @Post("/start")
    public HttpResponse start(@RequestObject Map<String, String> body) {
        String email = body.get("email");
        String deviceId = body.get("device_id");
        if (email == null || deviceId == null) {
            return error(HttpStatus.BAD_REQUEST, "email and device_id required");
        }
        try {
            if (!authRepo.isEmailVerified(email)) {
                return error(HttpStatus.FORBIDDEN, "Email must be verified before starting trial");
            }
            UUID userId = authRepo.getUserId(email);
            if (userId == null) return error(HttpStatus.NOT_FOUND, "User not found");

            boolean started = trialRepo.startTrial(userId, deviceId);
            if (!started) {
                return error(HttpStatus.CONFLICT,
                    "Trial already used on this device. Visit https://localcloud.dev/pricing for a license.");
            }

            // Issue a trial API key for the user
            String trialKey = keyRepo.generateOnlineKey(userId, "trial");
            logger.info("Trial started for {} on device {}", email, deviceId.substring(0, 8) + "...");
            return ok(Map.of(
                "key", trialKey,
                "tier", "trial",
                "message", "14-day free trial started. Save your key: LOCALCLOUD_API_KEY=" + trialKey));
        } catch (Exception e) {
            logger.error("Trial start failed for {}: {}", email, e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Trial start failed");
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
