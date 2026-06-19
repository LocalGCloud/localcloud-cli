package com.localcloud.emulators.cloudrun;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.docker.ContainerManager;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudRunRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(CloudRunRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("cloudrun")) return;

        ContainerManager cm = ctx.containerManager();
        if (cm == null || cm.getDockerClient() == null) {
            logger.warn("Docker unavailable — Cloud Run will use simulated mode");
            cm = new ContainerManager(null);
        }
        var emulator = new CloudRunEmulator(ctx.dataSource(), cm, ctx.credentialBroker());
        emulator.start();
        grpc.addService(emulator.getServicesService());
        grpc.addService(emulator.getRevisionsService());
        grpc.addService(emulator.getJobsService());
        ctx.gateway().registerGrpcEmulator(emulator,
                emulator.getServicesService(), emulator.getRevisionsService(), emulator.getJobsService());
        logger.info("Cloud Run facade registered");
    }
}
