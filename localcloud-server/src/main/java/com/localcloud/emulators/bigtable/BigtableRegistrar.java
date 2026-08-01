package com.localcloud.emulators.bigtable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.localcloud.admin.BigtableGrpcClient;
import com.localcloud.emulators.common.RegexRouteHelper;
import com.localcloud.emulators.common.ServiceRegistrar;
import com.localcloud.emulators.common.ServiceRegistrationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BigtableRegistrar implements ServiceRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(BigtableRegistrar.class);

    @Override
    public void registerRoutes(ServerBuilder sb, GrpcServiceBuilder grpc,
                               ServiceRegistrationContext ctx) throws Exception {
        if (!ctx.config().isServiceEnabled("bigtable")) return;

        int emulatorPort = ctx.config().getServiceRegistry().getService("bigtable") != null
                ? ctx.config().getServiceRegistry().getService("bigtable").port() : 24084;

        var emulator = new BigtableEmulator(emulatorPort, ctx.config().getGatewayPort(),
                ctx.iamPolicyRestHandler());
        emulator.start();

        // Verify emulator is reachable before registering routes
        try (BigtableGrpcClient probe = new BigtableGrpcClient(emulatorPort)) {
            probe.listInstancesWithDetails(ctx.config().getProjectId());
            logger.info("Bigtable emulator reachable on port {}", emulatorPort);
        } catch (Exception e) {
            logger.warn("Bigtable emulator not reachable on port {} — routes registered but may fail: {}",
                    emulatorPort, e.getMessage());
        }
        sb.annotatedService("/bigtable/admin/v2", emulator.getAdminService());
        sb.annotatedService("/v2", emulator.getAdminService());
        ctx.seedService().setBigtableEmulator(emulator);
        ctx.mutateService().setBigtableEmulator(emulator);
        ctx.gateway().registerRestEmulator("/bigtable/admin/v2", emulator, null);
        logger.info("Bigtable Admin API facade registered (emulator port: {})", emulatorPort);

        // modifyColumnFamilies via RegexRouteHelper — works around Armeria annotation parser limitation
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/bigtable/admin/v2/projects/{project}/instances/{instance}/tables/{table}:modifyColumnFamilies",
                (c, agg) -> emulator.getAdminService().modifyColumnFamilies(
                        c.pathParam("project"), c.pathParam("instance"), c.pathParam("table"),
                        agg.contentUtf8()));
        RegexRouteHelper.registerVerbRoute(sb, HttpMethod.POST,
                "/v2/projects/{project}/instances/{instance}/tables/{table}:modifyColumnFamilies",
                (c, agg) -> emulator.getAdminService().modifyColumnFamilies(
                        c.pathParam("project"), c.pathParam("instance"), c.pathParam("table"),
                        agg.contentUtf8()));
        logger.info("Bigtable modifyColumnFamilies routes registered");
    }
}
