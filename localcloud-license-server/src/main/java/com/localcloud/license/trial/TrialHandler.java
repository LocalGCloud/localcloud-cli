package com.localcloud.license.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.keys.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

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

    @Get("/status")
    public HttpResponse status() {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of("status", "ok")));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
