package com.localcloud.emulators.bigtable;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;

public class BigtableEmulator extends AbstractEmulator {

    private final BigtableAdminService adminService;

    public BigtableEmulator(int emulatorPort, int gatewayPort) {
        this(emulatorPort, gatewayPort, null);
    }

    public BigtableEmulator(int emulatorPort, int gatewayPort, IAMPolicyRestHandler iamHandler) {
        super("bigtable", "Bigtable", gatewayPort, "rest", "BIGTABLE_EMULATOR_HOST");
        this.adminService = new BigtableAdminService(emulatorPort, this, iamHandler);
    }

    public BigtableAdminService getAdminService() {
        return adminService;
    }

    @Override
    protected void doStart() {
        logger.info("Bigtable Admin REST facade ready (emulator port: {})", 
                adminService != null ? "configured" : "unknown");
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doReset() {
        // Reset is handled by the external emulator via gRPC (BigtableGrpcClient.resetProject)
    }
}
