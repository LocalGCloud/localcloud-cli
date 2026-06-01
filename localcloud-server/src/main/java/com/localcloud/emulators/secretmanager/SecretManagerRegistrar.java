package com.localcloud.emulators.secretmanager;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecretManagerRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(SecretManagerRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("secretmanager")) return;

        var emulator = new SecretManagerEmulator(ctx.dataSource());
        emulator.start();
        grpc.addService(emulator.getServiceImpl());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getServiceImpl());
        sb.annotatedService("/v1", new SecretManagerRestService(
                emulator.getStore(), emulator, ctx.iamPolicyRestHandler()));
        logger.info("Secret Manager facade registered");
    }
}
