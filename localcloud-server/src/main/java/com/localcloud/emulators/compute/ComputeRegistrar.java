package com.localcloud.emulators.compute;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.docker.ContainerManager;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ComputeRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(ComputeRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("compute")) return;

        ContainerManager cm = ctx.containerManager();
        if (cm == null || cm.getDockerClient() == null) {
            logger.warn("Docker unavailable — Compute Engine will use simulated mode");
            cm = new ContainerManager(null);
        }
        var emulator = new ComputeEmulator(ctx.dataSource(), cm,
                ctx.credentialBroker(), ctx.iamPolicyRestHandler());
        emulator.start();
        ctx.gateway().registerRestEmulator("/compute/v1", emulator, null);
        sb.annotatedService("/compute/v1", emulator.getRestService());
        logger.info("Compute Engine facade registered");
    }
}
