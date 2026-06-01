package com.localcloud.emulators.cloudtasks;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudTasksRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(CloudTasksRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("cloudtasks")) return;

        var emulator = new CloudTasksEmulator(ctx.dataSource());
        emulator.start();
        grpc.addService(emulator.getServiceImpl());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getServiceImpl());
        sb.annotatedService("/v2", new CloudTasksRestService(
                emulator.getStore(), emulator, ctx.iamPolicyRestHandler()));
        logger.info("Cloud Tasks facade registered");
    }
}
