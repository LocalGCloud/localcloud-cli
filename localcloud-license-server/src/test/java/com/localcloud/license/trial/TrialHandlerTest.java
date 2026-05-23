package com.localcloud.license.trial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.keys.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class TrialHandlerTest {

    private TrialHandler trialHandler;
    private TrialRepository trialRepo;
    private AuthRepository authRepo;
    private ApiKeyRepository keyRepo;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        trialRepo = Mockito.mock(TrialRepository.class);
        authRepo = Mockito.mock(AuthRepository.class);
        keyRepo = Mockito.mock(ApiKeyRepository.class);
        trialHandler = new TrialHandler(trialRepo, authRepo, keyRepo);
    }

    @Test
    void testStartTrialFailsIfTrialAlreadyExists() throws Exception {
        String email = "test@example.com";
        UUID userId = UUID.randomUUID();

        when(authRepo.isEmailVerified(email)).thenReturn(true);
        when(authRepo.getUserId(email)).thenReturn(userId);
        
        // Handler calls startTrial() which returns false when trial already exists
        when(trialRepo.startTrial(eq(userId), any())).thenReturn(false);

        Map<String, String> body = Map.of("email", email, "device_id", "device123");
        AggregatedHttpResponse response = trialHandler.start(body).aggregate().join();

        assertEquals(HttpStatus.CONFLICT, response.status());
        JsonNode json = mapper.readTree(response.contentUtf8());
        assertEquals("Trial already used on this device. Visit https://localcloud.dev/pricing for a license.",
                json.get("error").asText());
    }

    @Test
    void testStartTrialSucceedsIfNoTrialExists() throws Exception {
        String email = "test@example.com";
        UUID userId = UUID.randomUUID();

        when(authRepo.isEmailVerified(email)).thenReturn(true);
        when(authRepo.getUserId(email)).thenReturn(userId);
        
        when(trialRepo.startTrial(eq(userId), any())).thenReturn(true);
        // keyRepo.generateOnlineKey must return non-null (Map.of rejects null values)
        when(keyRepo.generateOnlineKey(eq(userId), eq("trial"))).thenReturn("trial-key-abc123");

        Map<String, String> body = Map.of("email", email, "device_id", "device123");
        AggregatedHttpResponse response = trialHandler.start(body).aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
    }
}
