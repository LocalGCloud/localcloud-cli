package com.localcloud.license.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.*;

import java.util.Map;

@ProducesJson
public class LicenseValidationHandler {

    private final LicenseValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    public LicenseValidationHandler(LicenseValidator validator) {
        this.validator = validator;
    }

    /** POST /license/validate — body: {key, device_id} */
    @Post("/validate")
    public HttpResponse validate(@RequestObject Map<String, String> body) {
        String key = body.get("key");
        String deviceId = body.get("device_id");
        var result = validator.validate(key, deviceId);
        if (!result.valid()) {
            try {
                return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.JSON_UTF_8,
                    mapper.writeValueAsString(Map.of("error", result.errorMessage())));
            } catch (Exception e) { return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR); }
        }
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of(
                    "tier", result.tier(),
                    "email", result.email(),
                    "expires", result.expiresEpoch())));
        } catch (Exception e) { return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR); }
    }
}
