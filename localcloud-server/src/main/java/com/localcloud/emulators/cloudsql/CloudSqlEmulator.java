package com.localcloud.emulators.cloudsql;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

/**
 * Cloud SQL Admin API facade. This slice models the control plane and exposes
 * PostgreSQL/OpenHalo data-plane metadata without installing a MySQL runtime.
 */
public class CloudSqlEmulator extends AbstractEmulator {

    private final CloudSqlStore store;
    private final CloudSqlRestService restService;

    public CloudSqlEmulator(PostgresDataSource dataSource, int gatewayPort) {
        super("cloudsql", "Cloud SQL", gatewayPort, "rest", "CLOUD_SQL_EMULATOR_HOST");
        this.store = new CloudSqlStore(dataSource);
        this.restService = new CloudSqlRestService(store, this);
    }

    public CloudSqlRestService getRestService() {
        return restService;
    }

    @Override
    protected void doStart() {
        logger.info("Cloud SQL Admin REST facade ready");
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doReset() {
        store.clearAll();
    }
}
