package com.localcloud.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.common.RestResponseHelper;

public final class RuntimeAdminService {
    private final RuntimeCatalogStore store;
    private final RuntimeBroker broker;

    public RuntimeAdminService(RuntimeCatalogStore store, RuntimeBroker broker) {
        this.store = store;
        this.broker = broker;
    }

    @Get("/runtime/profiles")
    public HttpResponse profiles() {
        ObjectNode response = RestResponseHelper.MAPPER.createObjectNode();
        response.set("profiles", RestResponseHelper.MAPPER.valueToTree(store.catalog().all()));
        response.set("aliases", RestResponseHelper.MAPPER.valueToTree(store.catalog().aliases()));
        return RestResponseHelper.ok(response);
    }

    @Get("/runtime/profiles/resolve")
    public HttpResponse resolve(@Param String selector) {
        try {
            return json(store.catalog().resolve(selector));
        } catch (IllegalArgumentException e) {
            return RestResponseHelper.error(404, e.getMessage());
        }
    }

    @Get("/runtime/status")
    public HttpResponse status() {
        ObjectNode response = RestResponseHelper.MAPPER.createObjectNode();
        response.put("mode", broker.provider().mode());
        response.put("available", broker.provider().available());
        response.put("publishedProfiles", store.catalog().published().size());
        return RestResponseHelper.ok(response);
    }

    @Post("/runtime/admin/profiles/import")
    public HttpResponse importCandidate(String body) {
        try {
            RuntimeProfile profile = RestResponseHelper.MAPPER.readValue(body, RuntimeProfile.class);
            return json(HttpStatus.CREATED, store.importCandidate(profile));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Post("/runtime/admin/profiles/publish")
    public HttpResponse publish(String body) {
        try {
            JsonNode request = RestResponseHelper.MAPPER.readTree(body);
            String revisionId = required(request, "revisionId");
            String alias = request.path("alias").asText("");
            RuntimeProfile.Image image = RestResponseHelper.MAPPER.treeToValue(request.path("image"), RuntimeProfile.Image.class);
            return json(store.publish(revisionId, image, alias));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Post("/runtime/admin/profiles/deprecate")
    public HttpResponse deprecate(String body) {
        try {
            JsonNode request = RestResponseHelper.MAPPER.readTree(body);
            return json(store.deprecate(required(request, "revisionId")));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Post("/runtime/admin/profiles/alias")
    public HttpResponse setAlias(String body) {
        try {
            JsonNode request = RestResponseHelper.MAPPER.readTree(body);
            String alias = required(request, "alias");
            String revisionId = required(request, "revisionId");
            RuntimeProfile profile = store.catalog().find(revisionId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown profile revision: " + revisionId));
            var updated = store.publish(revisionId, profile.image(), alias);
            return json(updated);
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static HttpResponse json(Object value) { return json(HttpStatus.OK, value); }
    private static HttpResponse json(HttpStatus status, Object value) {
        try {
            return HttpResponse.of(status, MediaType.JSON, RestResponseHelper.MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Serialization failed: " + e.getMessage());
        }
    }
}
