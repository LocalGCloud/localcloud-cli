package com.localcloud.emulators.dataproc;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.RegexRouteHelper;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataprocRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(DataprocRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("dataproc")) return;

        var emulator = new DataprocEmulator(ctx.dataSource(), ctx.runtimeCatalog(), ctx.runtimeBroker(),
                ctx.config().getDataDir().resolve("runtime"), ctx.containerManager(),
                ctx.config().getDataprocRegistry());
        emulator.start();
        grpc.addService(emulator.getClusterService());
        grpc.addService(emulator.getJobService());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getClusterService(), emulator.getJobService());
        sb.annotatedService("/v1", emulator.getRestService());
        var restService = emulator.getRestService();
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/v1/projects/{project}/regions/{region}/jobs/{jobId}:cancel",
                c -> restService.cancelJob(
                        c.pathParam("project"), c.pathParam("region"), c.pathParam("jobId")));
        ctx.seedService().setDataprocEmulator(emulator);
        logger.info("Dataproc facade registered");
    }
}
