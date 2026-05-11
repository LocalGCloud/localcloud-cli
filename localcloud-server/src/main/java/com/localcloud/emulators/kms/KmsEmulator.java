package com.localcloud.emulators.kms;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

/**
 * Cloud KMS facade emulator. The v1 implementation exposes REST endpoints with
 * local software-backed crypto; gRPC binding can be added on top of the same store.
 */
public class KmsEmulator extends AbstractEmulator {

    private final KmsStore store;
    private final KmsRestService restService;

    public KmsEmulator(PostgresDataSource dataSource, int gatewayPort) {
        super("kms", "Cloud KMS", gatewayPort, "rest", "CLOUD_KMS_EMULATOR_HOST");
        this.store = new KmsStore(dataSource);
        this.restService = new KmsRestService(store, this);
    }

    public KmsStore getStore() {
        return store;
    }

    public KmsRestService getRestService() {
        return restService;
    }

    @Override
    protected void doStart() {
        logger.info("Cloud KMS REST facade ready");
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doReset() {
        store.clearAll();
    }
}
