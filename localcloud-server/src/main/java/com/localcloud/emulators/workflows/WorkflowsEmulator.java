package com.localcloud.emulators.workflows;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

public class WorkflowsEmulator extends AbstractEmulator {
    private final WorkflowsStore store;
    private final WorkflowsServiceImpl workflowsService;
    private final ExecutionsServiceImpl executionsService;

    public WorkflowsEmulator(PostgresDataSource dataSource) {
        super("workflows", "Cloud Workflows", 8080, "grpc", "WORKFLOWS_EMULATOR_HOST");
        this.store = new WorkflowsStore(dataSource);
        this.workflowsService = new WorkflowsServiceImpl(store);
        this.executionsService = new ExecutionsServiceImpl(store);
    }

    public WorkflowsServiceImpl getWorkflowsService() { return workflowsService; }
    public ExecutionsServiceImpl getExecutionsService() { return executionsService; }
    public WorkflowsStore getStore() { return store; }

    @Override protected void doStart() throws Exception {
        logger.info("Workflows emulator initialized");
    }
    @Override protected void doStop() {
        workflowsService.shutdown();
    }
    @Override protected void doReset() {
        store.resetAll();
    }
}
