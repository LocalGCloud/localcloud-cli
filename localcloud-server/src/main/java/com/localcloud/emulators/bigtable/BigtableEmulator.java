package com.localcloud.emulators.bigtable;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import com.localcloud.persistence.PostgresDataSource;

public class BigtableEmulator extends AbstractEmulator {

    private final BigtableStore store;
    private final BigtableAdminService adminService;

    public BigtableEmulator(PostgresDataSource dataSource, int gatewayPort) {
        this(dataSource, gatewayPort, null);
    }

    public BigtableEmulator(PostgresDataSource dataSource, int gatewayPort, IAMPolicyRestHandler iamHandler) {
        super("bigtable", "Bigtable", gatewayPort, "rest", "BIGTABLE_EMULATOR_HOST");
        this.store = new BigtableStore(dataSource);
        this.adminService = new BigtableAdminService(store, this, iamHandler);
    }

    public BigtableAdminService getAdminService() {
        return adminService;
    }

    public BigtableStore getStore() {
        return store;
    }

    @Override
    protected void doStart() {
        logger.info("Bigtable Admin REST facade ready");
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doReset() {
        store.clearAll();
    }
}
