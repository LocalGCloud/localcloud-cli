package com.localcloud.emulators.scheduler;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.RegexRouteHelper;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudSchedulerRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(CloudSchedulerRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("cloudscheduler")) return;

        var emulator = new CloudSchedulerEmulator(ctx.dataSource());
        emulator.start();
        grpc.addService(emulator.getServiceImpl());
        sb.annotatedService("/v1", emulator.getRestService());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getServiceImpl());

        var rest = emulator.getRestService();
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/v1/projects/{project}/locations/{location}/jobs",
                (c, agg) -> rest.createJob(c.pathParam("project"), c.pathParam("location"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.GET,
                "/v1/projects/{project}/locations/{location}/jobs/{job}",
                c -> rest.getJob(c.pathParam("project"), c.pathParam("location"), c.pathParam("job")));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.DELETE,
                "/v1/projects/{project}/locations/{location}/jobs/{job}",
                c -> rest.deleteJob(c.pathParam("project"), c.pathParam("location"), c.pathParam("job")));
        ctx.seedService().setCloudSchedulerEmulator(emulator);
        logger.info("Cloud Scheduler facade registered");
    }
}
