package com.localcloud.emulators.monitoring;

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

public class MonitoringRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(MonitoringRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("monitoring")) return;

        var emulator = new MonitoringEmulator(ctx.dataSource());
        emulator.start();
        grpc.addService(emulator.getMonitoringService());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getMonitoringService());

        var policyRepo = new MonitoringAlertPolicyRepository(ctx.dataSource());

        sb.service(Route.builder().methods(HttpMethod.POST)
                .path("regex:^/v3/projects/(?<project>[^/]+)/alertPolicies$")
                .build(), (c, req) -> {
                    var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(req.aggregate().join().contentUtf8());
                    String displayName = parsed.has("displayName") ? parsed.get("displayName").asText() : "localcloud-alert";
                    String result = policyRepo.create(c.pathParam("project"), displayName);
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);
                });
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/v3/projects/(?<project>[^/]+)/alertPolicies/(?<policy>[^/]+)$")
                .build(), (c, req) -> {
                    String result = policyRepo.find(c.pathParam("project"), c.pathParam("policy"));
                    if (result == null) {
                        return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                                "{\"error\":{\"code\":404,\"message\":\"Alert policy not found\"}}");
                    }
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON, result);
                });
        sb.service(Route.builder().methods(HttpMethod.DELETE)
                .path("regex:^/v3/projects/(?<project>[^/]+)/alertPolicies/(?<policy>[^/]+)$")
                .build(), (c, req) -> {
                    boolean deleted = policyRepo.delete(c.pathParam("project"), c.pathParam("policy"));
                    if (!deleted) {
                        return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                                "{\"error\":{\"code\":404,\"message\":\"Alert policy not found\"}}");
                    }
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{}");
                });
        logger.info("Cloud Monitoring facade registered (with persisted alert policies)");
    }
}
