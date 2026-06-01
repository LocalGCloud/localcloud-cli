package com.localcloud.emulators.memorystore;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemorystoreRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(MemorystoreRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("memorystore")) return;

        var emulator = new MemorystoreEmulator(ctx.dataSource(), ctx.config().getGatewayPort(),
                ctx.iamPolicyRestHandler());
        emulator.start();
        sb.annotatedService("/redis/v1", emulator.getAdminService());
        ctx.seedService().setMemorystoreEmulator(emulator);
        ctx.mutateService().setMemorystoreEmulator(emulator);
        logger.info("Memorystore Admin API facade registered at /redis/v1");
    }
}
