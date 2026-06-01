package com.localcloud.emulators.logging;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(LoggingRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("logging")) return;

        var emulator = new LoggingEmulator(ctx.dataSource());
        emulator.start();
        grpc.addService(emulator.getLoggingService());
        grpc.addService(emulator.getConfigService());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getLoggingService());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getConfigService());

        var sinkRepo = new LoggingSinkRepository(ctx.dataSource());

        sb.service(Route.builder().methods(HttpMethod.POST)
                .path("regex:^/v2/projects/(?<project>[^/]+)/sinks$")
                .build(), (c, req) -> {
                    try {
                        var agg = req.aggregate().join();
                        String body = agg.contentUtf8();
                        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                        String sinkName = parsed.has("name") ? parsed.get("name").asText() : java.util.UUID.randomUUID().toString().substring(0, 8);
                        String destination = parsed.has("destination") ? parsed.get("destination").asText() : "bigquery.googleapis.com";
                        String result = sinkRepo.create(c.pathParam("project"), sinkName, destination);
                        return HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);
                    } catch (Exception e) {
                        logger.error("Failed to create logging sink: {}", e.getMessage(), e);
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                                "{\"error\":{\"code\":500,\"message\":\"" + e.getMessage() + "\"}}");
                    }
                });
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/v2/projects/(?<project>[^/]+)/sinks/(?<sink>[^/]+)$")
                .build(), (c, req) -> {
                    String result = sinkRepo.find(c.pathParam("project"), c.pathParam("sink"));
                    if (result == null) {
                        return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                                "{\"error\":{\"code\":404,\"message\":\"Sink not found\"}}");
                    }
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);
                });
        sb.service(Route.builder().methods(HttpMethod.DELETE)
                .path("regex:^/v2/projects/(?<project>[^/]+)/sinks/(?<sink>[^/]+)$")
                .build(), (c, req) -> {
                    boolean deleted = sinkRepo.delete(c.pathParam("project"), c.pathParam("sink"));
                    if (!deleted) {
                        return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                                "{\"error\":{\"code\":404,\"message\":\"Sink not found\"}}");
                    }
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}");
                });
        logger.info("Cloud Logging facade registered (with persisted sinks)");
    }
}
