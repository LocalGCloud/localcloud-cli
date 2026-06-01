package com.localcloud.emulators.cloudsql;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudSqlRegistrar implements ServiceRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(CloudSqlRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("cloudsql")) return;

        var emulator = new CloudSqlEmulator(ctx.dataSource(), ctx.config().getGatewayPort(),
                ctx.iamPolicyRestHandler());
        emulator.start();
        ctx.gateway().registerRestEmulator("/sql/v1", emulator, null);
        sb.annotatedService("/sql/v1", emulator.getRestService());
        sb.annotatedService("/sql/v1beta4", emulator.getRestService());
        sb.annotatedService("/sqladmin/v1", emulator.getRestService());
        sb.annotatedService("/sqladmin/v1beta4", emulator.getRestService());
        sb.annotatedService("/", emulator.getRestService());
        ctx.seedService().setCloudSqlEmulator(emulator);
        ctx.mutateService().setCloudSqlEmulator(emulator);
        logger.info("Cloud SQL Admin API facade registered");
    }
}
