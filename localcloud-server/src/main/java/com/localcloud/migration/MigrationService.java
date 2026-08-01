package com.localcloud.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.common.RestResponseHelper;

public final class MigrationService {
    private final MigrationRepository repository;
    private final MigrationEngine engine;

    public MigrationService(MigrationRepository repository, MigrationEngine engine) {
        this.repository = repository;
        this.engine = engine;
    }

    @Get("/migration/suites")
    public HttpResponse suites() {
        ObjectNode response = RestResponseHelper.MAPPER.createObjectNode();
        response.set("suites", RestResponseHelper.MAPPER.valueToTree(repository.listSuites()));
        return RestResponseHelper.ok(response);
    }

    @Post("/migration/suites")
    public HttpResponse createSuite(String body) {
        try {
            MigrationSuite requested = RestResponseHelper.MAPPER.readValue(body, MigrationSuite.class);
            return json(HttpStatus.CREATED, repository.saveSuite(requested));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Get("/migration/suites/{suiteId}")
    public HttpResponse suite(@Param String suiteId, @Param Integer revision) {
        try {
            MigrationSuite suite = repository.getSuite(suiteId, revision);
            return suite == null ? RestResponseHelper.error(404, "Migration suite not found") : json(HttpStatus.OK, suite);
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Get("/migration/runs")
    public HttpResponse runs() {
        return json(HttpStatus.OK, java.util.Map.of("runs", repository.listRuns()));
    }

    @Post("/migration/runs")
    public HttpResponse startRun(String body) {
        try {
            JsonNode request = RestResponseHelper.MAPPER.readTree(body);
            String suiteId = request.path("suiteId").asText("");
            if (suiteId.isBlank()) throw new IllegalArgumentException("suiteId is required");
            Integer revision = request.has("revision") ? request.path("revision").asInt() : null;
            MigrationSuite suite = repository.getSuite(suiteId, revision);
            if (suite == null) return RestResponseHelper.error(404, "Migration suite not found");
            return json(HttpStatus.ACCEPTED, engine.start(suite));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Get("/migration/runs/{runId}")
    public HttpResponse run(@Param String runId) {
        MigrationReport report = repository.getRun(runId);
        return report == null ? RestResponseHelper.error(404, "Migration run not found") : json(HttpStatus.OK, report);
    }

    @Get("/migration/runs/{runId}/report")
    public HttpResponse report(@Param String runId) {
        MigrationReport report = repository.getRun(runId);
        return report == null ? RestResponseHelper.error(404, "Migration run not found") : json(HttpStatus.OK, report);
    }

    @Post("/migration/runs/{runId}/cancel")
    public HttpResponse cancel(@Param String runId) {
        return engine.cancel(runId)
                ? json(HttpStatus.ACCEPTED, java.util.Map.of("runId", runId, "status", "cancelling"))
                : RestResponseHelper.error(409, "Migration run is not active");
    }

    @Post("/migration/runs/{runId}/retry")
    public HttpResponse retry(@Param String runId) {
        try {
            return json(HttpStatus.ACCEPTED, engine.retry(runId));
        } catch (IllegalArgumentException e) {
            return RestResponseHelper.error(404, e.getMessage());
        } catch (Exception e) {
            return RestResponseHelper.error(409, e.getMessage());
        }
    }

    @Post("/migration/runs/{runId}/cleanup")
    public HttpResponse cleanup(@Param String runId) {
        try {
            return json(HttpStatus.OK, engine.cleanup(runId));
        } catch (IllegalArgumentException e) {
            return RestResponseHelper.error(404, e.getMessage());
        } catch (Exception e) {
            return RestResponseHelper.error(409, e.getMessage());
        }
    }

    private static HttpResponse json(HttpStatus status, Object value) {
        try {
            return HttpResponse.of(status, MediaType.JSON, RestResponseHelper.MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Serialization failed: " + e.getMessage());
        }
    }
}
