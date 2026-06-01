package com.localcloud.emulators.functions;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.RegexRouteHelper;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudFunctionsRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(CloudFunctionsRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("cloudfunctions")) return;

        var emulator = new CloudFunctionsEmulator(ctx.dataSource());
        emulator.start();
        grpc.addService(emulator.getServiceImpl());
        sb.annotatedService("/v1", emulator.getRestService());
        sb.annotatedService("/v2", emulator.getRestService());
        ctx.gateway().registerGrpcEmulator(emulator, emulator.getServiceImpl());

        var rest = emulator.getRestService();
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/v2/projects/{project}/locations/{location}/functions",
                (c, agg) -> rest.createFunction(c, c.pathParam("project"), c.pathParam("location"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.GET,
                "/v2/projects/{project}/locations/{location}/functions/{function}",
                c -> rest.getFunction(c.pathParam("project"), c.pathParam("location"), c.pathParam("function")));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.DELETE,
                "/v2/projects/{project}/locations/{location}/functions/{function}",
                c -> rest.deleteFunction(c.pathParam("project"), c.pathParam("location"), c.pathParam("function")));
        ctx.seedService().setCloudFunctionsEmulator(emulator);
        logger.info("Cloud Functions facade registered");
    }
}
