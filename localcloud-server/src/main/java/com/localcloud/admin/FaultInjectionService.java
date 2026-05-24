package com.localcloud.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.gateway.FaultInjectionRegistry;

/**
 * Admin API for configuring local fault injection rules.
 */
public class FaultInjectionService {

    private final FaultInjectionRegistry registry;
    private final ObjectMapper mapper;

    public FaultInjectionService(FaultInjectionRegistry registry) {
        this.registry = registry;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Get("/faults")
    public HttpResponse listFaults() {
        try {
            var faults = registry.list();
            return json(HttpStatus.OK, Map.of(
                    "faults", faults,
                    "count", faults.size()
            ));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Post("/faults")
    public HttpResponse createFault(AggregatedHttpRequest request) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);
            var rule = registry.add(body);

            Map<String, Object> response = new LinkedHashMap<>(rule.toMap());
            response.put("status", "created");
            return json(HttpStatus.CREATED, response);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Delete("/faults/{id}")
    public HttpResponse deleteFault(@Param("id") String id) {
        try {
            boolean removed = registry.remove(id);
            if (!removed) {
                return error(HttpStatus.NOT_FOUND, "Fault rule not found: " + id);
            }
            return json(HttpStatus.OK, Map.of("status", "deleted", "id", id));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Delete("/faults")
    public HttpResponse clearFaults() {
        try {
            registry.clear();
            return json(HttpStatus.OK, Map.of("status", "cleared"));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private HttpResponse json(HttpStatus status, Object body) throws Exception {
        return HttpResponse.of(status, MediaType.JSON,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
    }

    private HttpResponse error(HttpStatus status, String message) {
        try {
            return json(status, Map.of("error", true, "message", message));
        } catch (Exception e) {
            return HttpResponse.of(status, MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
