package com.localcloud.emulators.cloudbilling;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudBillingRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(CloudBillingRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) {
        if (!ctx.config().isServiceEnabled("cloudbilling")) return;

        var cbService = new CloudBillingRestService(ctx.dataSource());
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/v1/projects/(?<projectId>[^/]+)/billingInfo$")
                .build(), (c, req) ->
                        cbService.getBillingInfo(c.pathParam("projectId")));
        sb.service(Route.builder().methods(HttpMethod.PUT)
                .path("regex:^/v1/projects/(?<projectId>[^/]+)/billingInfo$")
                .build(), (c, req) -> {
                    var agg = req.aggregate().join();
                    return cbService.updateBillingInfo(c.pathParam("projectId"), agg.contentUtf8());
                });
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/v1/billingAccounts$")
                .build(), (c, req) -> cbService.listBillingAccounts());
        sb.service(Route.builder().methods(HttpMethod.GET)
                .path("regex:^/v1/billingAccounts/(?<accountName>[^/]+)/projects$")
                .build(), (c, req) ->
                        cbService.listProjectBillingInfo(c.pathParam("accountName")));
        logger.info("Cloud Billing API endpoints registered via regex routes on /v1");
    }
}
