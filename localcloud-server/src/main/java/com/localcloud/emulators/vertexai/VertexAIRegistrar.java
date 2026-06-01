package com.localcloud.emulators.vertexai;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.RegexRouteHelper;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VertexAIRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(VertexAIRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("vertexai")) return;

        var emulator = new VertexAiEmulator(ctx.dataSource(), ctx.config().getGatewayPort(),
                ctx.iamPolicyRestHandler());
        emulator.start();
        sb.annotatedService("/v1", emulator.getRestService());
        ctx.gateway().registerRestEmulator("/v1/projects/*/locations/*/publishers/*/models", emulator, null);

        var vai = emulator.getRestService();
        String base = "/v1/projects/{project}/locations/{location}/publishers/{publisher}/models/{model}";
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                base + ":generateContent",
                (c, agg) -> vai.generateContent(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("publisher"), c.pathParam("model"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                base + ":streamGenerateContent",
                (c, agg) -> vai.streamGenerateContent(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("publisher"), c.pathParam("model"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                base + ":embedContent",
                (c, agg) -> vai.embedContent(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("publisher"), c.pathParam("model"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                base + ":countTokens",
                (c, agg) -> vai.countTokens(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("publisher"), c.pathParam("model"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                base + ":computeTokens",
                (c, agg) -> vai.computeTokens(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("publisher"), c.pathParam("model"), agg.contentUtf8()));
        logger.info("Vertex AI facade registered");
    }
}
