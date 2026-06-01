package com.localcloud.emulators.gke;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GKERegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(GKERegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("gke")) return;

        var k3dManager = new K3dManager(ctx.credentialBroker());
        var emulator = new GkeEmulator(ctx.dataSource(), k3dManager);
        emulator.start();
        grpc.addService(emulator.getClusterManagerService());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getClusterManagerService());
        logger.info("GKE facade registered");
    }
}
