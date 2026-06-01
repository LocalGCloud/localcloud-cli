package com.localcloud.emulators.kms;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.emulators.common.RegexRouteHelper;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KMSRegistrar implements ServiceRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(KMSRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("kms")) return;

        var emulator = new KmsEmulator(ctx.dataSource(), ctx.config().getGatewayPort(),
                ctx.iamPolicyRestHandler());
        emulator.start();
        sb.annotatedService("/v1", emulator.getRestService());
        ctx.gateway().registerRestEmulator("/v1/projects/*/locations/*/keyRings", emulator, null);

        var kms = emulator.getRestService();
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/v1/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:encrypt",
                (c, agg) -> kms.encrypt(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("keyRing"), c.pathParam("cryptoKey"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/v1/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:decrypt",
                (c, agg) -> kms.decrypt(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("keyRing"), c.pathParam("cryptoKey"), agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/v1/projects/{project}/locations/{location}/keyRings/{keyRing}/cryptoKeys/{cryptoKey}:updateCryptoKeyPrimaryVersion",
                (c, agg) -> kms.updatePrimaryVersion(c.pathParam("project"), c.pathParam("location"),
                        c.pathParam("keyRing"), c.pathParam("cryptoKey"), agg.contentUtf8()));

        // destroyVersion and restoreVersion use async HttpResponse.of(thenApply) pattern —
        // kept manual since RegexRouteHelper assumes synchronous aggregation
        sb.service(Route.builder().methods(HttpMethod.POST)
                .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^/]+)/cryptoKeyVersions/(?<version>[^:]+):destroy$")
                .build(), (c, req) -> {
                    String p = c.pathParam("project"), l = c.pathParam("location");
                    String kr = c.pathParam("keyRing"), ck = c.pathParam("cryptoKey");
                    String v = c.pathParam("version");
                    return HttpResponse.of(req.aggregate().thenApply(agg ->
                            kms.destroyVersion(p, l, kr, ck, v)));
                });
        sb.service(Route.builder().methods(HttpMethod.POST)
                .path("regex:^/v1/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/keyRings/(?<keyRing>[^/]+)/cryptoKeys/(?<cryptoKey>[^/]+)/cryptoKeyVersions/(?<version>[^:]+):restore$")
                .build(), (c, req) -> {
                    String p = c.pathParam("project"), l = c.pathParam("location");
                    String kr = c.pathParam("keyRing"), ck = c.pathParam("cryptoKey");
                    String v = c.pathParam("version");
                    return HttpResponse.of(req.aggregate().thenApply(agg ->
                            kms.restoreVersion(p, l, kr, ck, v)));
                });
        logger.info("Cloud KMS facade registered");
    }
}
