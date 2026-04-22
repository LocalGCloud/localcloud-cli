package com.localcloud.emulators.workflows;

import com.localcloud.emulators.workflows.engine.WorkflowParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class WorkflowFileWatcher implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowFileWatcher.class);
    private final Path directory;
    private final WorkflowsStore store;
    private final String projectId;
    private final String locationId;
    private volatile boolean running = true;

    public WorkflowFileWatcher(Path directory, WorkflowsStore store, String projectId, String locationId) {
        this.directory = directory;
        this.store = store;
        this.projectId = projectId;
        this.locationId = locationId;
    }

    public void stop() { running = false; }

    @Override
    public void run() {
        // Initial scan
        try (var stream = Files.list(directory)) {
            stream.filter(this::isWorkflowFile).forEach(this::loadFile);
        } catch (IOException e) {
            logger.warn("Failed to scan workflows directory {}: {}", directory, e.getMessage());
        }

        // Watch for changes
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            logger.info("Watching {} for workflow file changes", directory);

            while (running) {
                WatchKey key;
                try {
                    key = watcher.poll(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = directory.resolve((Path) event.context());
                    if (!isWorkflowFile(changed)) continue;

                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        deleteFile(changed);
                    } else {
                        loadFile(changed);
                    }
                }
                key.reset();
            }
        } catch (IOException e) {
            logger.error("Workflow file watcher failed for {}: {}", directory, e.getMessage());
        }
    }

    private boolean isWorkflowFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    private void loadFile(Path path) {
        try {
            String source = Files.readString(path);
            WorkflowParser.parse(source); // Validate
            String workflowId = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            store.upsertWorkflow(projectId, locationId, workflowId, source);
            logger.info("Loaded workflow '{}' from {}", workflowId, path.getFileName());
        } catch (Exception e) {
            logger.warn("Failed to load workflow from {}: {}", path.getFileName(), e.getMessage());
        }
    }

    private void deleteFile(Path path) {
        try {
            String workflowId = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            store.deleteWorkflow(projectId, locationId, workflowId);
            logger.info("Deleted workflow '{}' (file removed: {})", workflowId, path.getFileName());
        } catch (Exception e) {
            logger.warn("Failed to delete workflow for {}: {}", path.getFileName(), e.getMessage());
        }
    }
}
