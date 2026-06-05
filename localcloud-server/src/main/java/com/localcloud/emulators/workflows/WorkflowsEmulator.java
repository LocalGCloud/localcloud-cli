package com.localcloud.emulators.workflows;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkflowsEmulator extends AbstractEmulator {
    private final WorkflowsStore store;
    private final WorkflowsServiceImpl workflowsService;
    private final ExecutionsServiceImpl executionsService;
    private WorkflowFileWatcher fileWatcher;

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

        // Recover orphaned executions from previous instance
        try {
            int recovered = store.sweepOrphanedExecutions();
            if (recovered > 0) {
                logger.info("Recovered {} orphaned executions (marked as FAILED after restart)", recovered);
            }
        } catch (Exception e) {
            logger.warn("Failed to sweep orphaned executions: {}", e.getMessage());
        }

        // Purge soft-deleted workflows older than 30 days
        try {
            int purged = store.purgeDeletedWorkflows();
            if (purged > 0) {
                logger.info("Purged {} soft-deleted workflows (older than 30 days)", purged);
            }
        } catch (Exception e) {
            logger.warn("Failed to purge deleted workflows: {}", e.getMessage());
        }

        String workflowsDir = System.getenv("LOCALCLOUD_WORKFLOWS_DIR");
        if (workflowsDir != null && !workflowsDir.isBlank()) {
            Path dir = Path.of(workflowsDir);
            if (Files.isDirectory(dir)) {
                String projectId = System.getenv().getOrDefault("LOCALCLOUD_PROJECT", "local-project");
                fileWatcher = new WorkflowFileWatcher(dir, store, projectId, "us-central1");
                Thread watchThread = new Thread(fileWatcher, "workflow-file-watcher");
                watchThread.setDaemon(true);
                watchThread.start();
                logger.info("Workflow hot-reload enabled for directory: {}", dir);
            } else {
                logger.warn("LOCALCLOUD_WORKFLOWS_DIR={} is not a valid directory, hot-reload disabled", workflowsDir);
            }
        }
    }
    @Override protected void doStop() {
        if (fileWatcher != null) fileWatcher.stop();
        workflowsService.shutdown();
    }
    @Override protected void doReset() {
        store.resetAll();
    }
}
