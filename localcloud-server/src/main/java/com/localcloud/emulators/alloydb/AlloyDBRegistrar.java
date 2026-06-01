package com.localcloud.emulators.alloydb;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlloyDBRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(AlloyDBRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("alloydb")) return;

        var emulator = new AlloyDBEmulator(ctx.dataSource());
        emulator.start();
        grpc.addService(emulator.getServiceImpl());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getServiceImpl());
        sb.annotatedService("/v1", emulator.getRestService());
        ctx.seedService().setAlloyDBEmulator(emulator);
        logger.info("AlloyDB facade registered");
    }
}
