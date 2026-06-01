package com.localcloud.emulators.pubsub;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PubSubRestRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(PubSubRestRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) {
        if (!ctx.config().isServiceEnabled("pubsub")) return;

        var store = new PubSubStore(ctx.dataSource().getDataSource());
        var restService = new PubSubRestService(store);
        sb.annotatedService("/v1", restService);
        sb.annotatedService("/", restService);
        logger.info("Pub/Sub Admin API REST facade registered at /v1 and /");
    }
}
