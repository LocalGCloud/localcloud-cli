package com.localcloud.license.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@ProducesJson
public class LicenseValidationHandler {

    private static final Logger logger = LoggerFactory.getLogger(LicenseValidationHandler.class);
    private final LicenseValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    public LicenseValidationHandler(LicenseValidator validator) {
        this.validator = validator;
    }

    @Get("/validate")
    public HttpResponse validate(
            @Param("key") String key,
            @Param("device") String device) {
        try {
            var result = validator.validate(key, device);
            if (!result.valid()) {
                return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.JSON_UTF_8,
                    mapper.writeValueAsString(Map.of("valid", false, "reason", result.reason())));
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of(
                    "valid", true,
                    "tier", result.tier(),
                    "userId", result.userId().toString()
                )));
        } catch (Exception e) {
            logger.error("Validation error: {}", e.getMessage());
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
