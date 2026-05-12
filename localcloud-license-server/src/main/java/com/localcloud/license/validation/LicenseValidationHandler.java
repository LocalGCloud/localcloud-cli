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
    private final JwtSigner signer;
    private final KeyPairManager keyPairManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public LicenseValidationHandler(LicenseValidator validator, JwtSigner signer,
                                    KeyPairManager keyPairManager) {
        this.validator = validator;
        this.signer = signer;
        this.keyPairManager = keyPairManager;
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
            String jwt = signer.sign(result.tier(), result.email(),
                    deviceId != null ? deviceId : "", result.expiresEpoch());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of("token", jwt)));
        } catch (Exception e) { return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    /** GET /license/public-key — returns the RS256 public key used to verify validation tokens. */
    @Get("/public-key")
    public HttpResponse publicKey() {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of(
                    "algorithm", "RS256",
                    "key", keyPairManager.getPublicKeyBase64())));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
