package com.localcloud.emulators.memorystore;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

public class MemorystoreEmulator extends AbstractEmulator {

    private final MemorystoreStore store;
    private final MemorystoreAdminService adminService;

    public MemorystoreEmulator(PostgresDataSource dataSource, int gatewayPort) {
        super("memorystore", "Memorystore", gatewayPort, "rest", "REDIS_HOST");
        this.store = new MemorystoreStore(dataSource);
        this.adminService = new MemorystoreAdminService(store, this);
    }

    public MemorystoreAdminService getAdminService() {
        return adminService;
    }

    public MemorystoreStore getStore() {
        return store;
    }

    @Override
    protected void doStart() {
        logger.info("Memorystore Admin REST facade ready");
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doReset() {
        store.clearAll();
    }
}
