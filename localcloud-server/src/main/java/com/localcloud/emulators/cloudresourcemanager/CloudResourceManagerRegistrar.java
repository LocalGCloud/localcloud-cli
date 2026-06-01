package com.localcloud.emulators.cloudresourcemanager;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudResourceManagerRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(CloudResourceManagerRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) {
        if (!ctx.config().isServiceEnabled("cloudresourcemanager")) return;

        var v1Service = new CloudResourceManagerRestService(ctx.projectService(), ctx.config(), "v1");
        var v3Service = new CloudResourceManagerRestService(ctx.projectService(), ctx.config(), "v3");
        sb.annotatedService("/v1", v1Service);
        sb.annotatedService("/v3", v3Service);
        logger.info("Cloud Resource Manager facade registered at /v1 and /v3");
    }
}
