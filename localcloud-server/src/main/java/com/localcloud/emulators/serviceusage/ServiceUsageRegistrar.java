package com.localcloud.emulators.serviceusage;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceUsageRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(ServiceUsageRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) {
        if (!ctx.config().isServiceEnabled("serviceusage")) return;

        var suService = new ServiceUsageRestService();
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/v1/projects/(?<project>[^/]+)/services/(?<service>[^/]+)$")
                .build(), (c, req) ->
                        suService.getService(c.pathParam("project"), c.pathParam("service")));
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/v1/projects/(?<project>[^/]+)/services$")
                .build(), (c, req) -> suService.listServices(c.pathParam("project")));
        sb.service(Route.builder().methods(HttpMethod.POST)
                .path("regex:^/v1/projects/(?<project>[^/]+)/services/(?<service>[^:]+):enable$")
                .build(), (c, req) -> suService.enableService(c.pathParam("project"), c.pathParam("service")));
        sb.service(Route.builder().methods(HttpMethod.POST)
                .path("regex:^/v1/projects/(?<project>[^/]+)/services:batchEnable$")
                .build(), (c, req) -> {
                    var agg = req.aggregate().join();
                    return suService.batchEnableServices(c.pathParam("project"), agg.contentUtf8());
                });
        logger.info("Service Usage API endpoints registered via regex routes on /v1");
    }
}
