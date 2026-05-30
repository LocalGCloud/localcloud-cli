package com.localcloud.emulators.compute;

import com.localcloud.admin.CredentialBroker;
import com.localcloud.docker.ContainerManager;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import com.localcloud.persistence.PostgresDataSource;

/**
 * Compute Engine emulator. Creates real Docker containers as VM instances.
 */
public class ComputeEmulator extends AbstractEmulator {

    private final PostgresDataSource dataSource;
    private final ContainerManager containerManager;
    private final CredentialBroker credentialBroker;
    private final ComputeStore store;
    private final ComputeRestService restService;

    public ComputeEmulator(PostgresDataSource dataSource, ContainerManager containerManager) {
        this(dataSource, containerManager, null, null);
    }

    public ComputeEmulator(PostgresDataSource dataSource, ContainerManager containerManager, CredentialBroker credentialBroker) {
        this(dataSource, containerManager, credentialBroker, null);
    }

    public ComputeEmulator(PostgresDataSource dataSource, ContainerManager containerManager, CredentialBroker credentialBroker, IAMPolicyRestHandler iamHandler) {
        super("compute", "Compute Engine", 8080, "rest", "COMPUTE_EMULATOR_HOST");
        this.dataSource = dataSource;
        this.containerManager = containerManager;
        this.credentialBroker = credentialBroker;
        this.store = new ComputeStore(dataSource);
        this.restService = new ComputeRestService(store, containerManager, this, iamHandler);
    }

    public CredentialBroker getCredentialBroker() {
        return credentialBroker;
    }

    @Override
    protected void doStart() throws Exception {
        logger.info("Compute Engine emulator REST services ready");
    }

    @Override
    protected void doStop() {
        // Stop all managed compute containers
        try {
            var containers = containerManager.listByLabel("localcloud.service", "compute");
            for (var container : containers) {
                containerManager.stop(container.getId());
                containerManager.remove(container.getId());
            }
        } catch (Exception e) {
            logger.warn("Error cleaning up compute containers: {}", e.getMessage());
        }
    }

    @Override
    protected void doReset() {
        doStop();
        try {
            store.deleteAll();
        } catch (Exception e) {
            logger.error("Failed to reset Compute Engine data", e);
        }
    }

    public ComputeRestService getRestService() {
        return restService;
    }
}
