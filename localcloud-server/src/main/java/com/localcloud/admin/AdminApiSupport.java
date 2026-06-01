package com.localcloud.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import java.util.Map;

/**
 * Shared utilities for admin API services: Jackson mapper, error response
 * helper, and constants. Extracted from AdminApiService during the
 * service split refactoring.
 */
public final class AdminApiSupport {

    public static final int DEFAULT_REQUEST_LIMIT = 100;
    public static final int MAX_REQUEST_LIMIT = 1000;
    public static final String UPGRADE_URL = "https://localcloud.dev/pricing";

    /** Map service IDs to supervisord program names (external services only). */
    public static final Map<String, String> SUPERVISOR_PROGRAM_NAMES = Map.of(
        "gcs", "fake-gcs-server",
        "pubsub", "pubsub-emulator",
        "firestore", "firestore-emulator",
        "bigtable", "bigtable-emulator",
        "spanner", "spanner-emulator",
        "bigquery", "bigquery-emulator"
    );

    private static final ObjectMapper MAPPER = createMapper();

    private static ObjectMapper createMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private AdminApiSupport() {}

    public static ObjectMapper mapper() { return MAPPER; }

    public static HttpResponse errorResponse(Exception e) {
        try {
            Map<String, Object> error = Map.of(
                    "error", true,
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            );
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.JSON, MAPPER.writeValueAsString(error));
        } catch (Exception ex) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
        }
    }
}
