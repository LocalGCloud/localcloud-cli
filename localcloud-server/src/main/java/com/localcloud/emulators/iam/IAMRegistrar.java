package com.localcloud.emulators.iam;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import io.grpc.ServerInterceptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IAMRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(IAMRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("cloudiam")) return;

        var emulator = new IAMEmulator(ctx.dataSource(), ctx.config().isIamLogWarningsEnabled());
        emulator.start();
        var iamService = ServerInterceptors.intercept(
                emulator.getServiceImpl(), IAMEmulator.warningInterceptor());
        grpc.addService(iamService);
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getServiceImpl());
        ctx.seedService().setIAMEmulator(emulator);
        logger.info("Cloud IAM facade registered");
    }
}
