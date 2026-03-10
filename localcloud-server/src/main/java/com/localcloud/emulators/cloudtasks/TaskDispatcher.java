package com.localcloud.emulators.cloudtasks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scheduled executor that polls for dispatchable tasks and dispatches
 * HTTP requests to the task target URLs.
 */
public class TaskDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(TaskDispatcher.class);

    private final CloudTasksStore store;
    private final ScheduledExecutorService scheduler;
    private final HttpClient httpClient;

    public TaskDispatcher(CloudTasksStore store) {
        this.store = store;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloud-tasks-dispatcher");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Start polling for dispatchable tasks every second.
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::dispatchCycle, 1, 1, TimeUnit.SECONDS);
        logger.info("Task dispatcher started (polling every 1 second)");
    }

    /**
     * Stop the dispatcher.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Task dispatcher stopped");
    }

    private void dispatchCycle() {
        try {
            List<String> queueNames = store.getActiveQueueNames();
            for (String queueName : queueNames) {
                // Check if queue is paused
                try {
                    String[] parts = CloudTasksStore.parseQueueName(queueName);
                    String queueState = store.getQueueState(parts[0], parts[1], parts[2]);
                    if ("PAUSED".equals(queueState)) {
                        continue;
                    }
                } catch (SQLException e) {
                    logger.warn("Failed to check queue state for {}: {}", queueName, e.getMessage());
                    continue;
                }

                List<CloudTasksStore.TaskEntry> tasks = store.getDispatchableTasks(queueName);
                for (CloudTasksStore.TaskEntry task : tasks) {
                    dispatchTask(task);
                }
            }
        } catch (Exception e) {
            logger.error("Error in dispatch cycle", e);
        }
    }

    private void dispatchTask(CloudTasksStore.TaskEntry task) {
        if (task.httpUrl == null || task.httpUrl.isEmpty()) {
            logger.warn("Task {} has no HTTP URL, marking as FAILED", task.taskId);
            task.state = "FAILED";
            return;
        }

        task.state = "RUNNING";
        task.dispatchCount++;
        task.lastAttemptTime = Instant.now();

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(task.httpUrl))
                    .timeout(Duration.ofSeconds(30));

            // Add headers
            if (task.httpHeaders != null) {
                for (Map.Entry<String, String> header : task.httpHeaders.entrySet()) {
                    reqBuilder.header(header.getKey(), header.getValue());
                }
            }

            // Set method and body
            HttpRequest.BodyPublisher bodyPublisher = task.httpBody != null && task.httpBody.length > 0
                    ? HttpRequest.BodyPublishers.ofByteArray(task.httpBody)
                    : HttpRequest.BodyPublishers.noBody();

            reqBuilder = switch (task.httpMethod != null ? task.httpMethod.toUpperCase() : "POST") {
                case "GET" -> reqBuilder.GET();
                case "PUT" -> reqBuilder.PUT(bodyPublisher);
                case "DELETE" -> reqBuilder.DELETE();
                case "PATCH" -> reqBuilder.method("PATCH", bodyPublisher);
                case "HEAD" -> reqBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                case "OPTIONS" -> reqBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                default -> reqBuilder.POST(bodyPublisher); // POST is the default
            };

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            task.responseCount++;
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                task.state = "COMPLETED";
                logger.debug("Task {} completed with status {}", task.taskId, statusCode);
            } else {
                handleFailure(task, "HTTP " + statusCode);
            }
        } catch (Exception e) {
            handleFailure(task, e.getMessage());
        }
    }

    private void handleFailure(CloudTasksStore.TaskEntry task, String reason) {
        // Simple retry with exponential backoff
        if (task.dispatchCount < 3) {
            // Reschedule with backoff: 2^dispatchCount seconds
            long backoffSeconds = (long) Math.pow(2, task.dispatchCount);
            task.scheduleTime = Instant.now().plusSeconds(backoffSeconds);
            task.state = "PENDING";
            logger.debug("Task {} failed ({}), retrying in {}s (attempt {}/3)",
                    task.taskId, reason, backoffSeconds, task.dispatchCount);
        } else {
            task.state = "FAILED";
            logger.warn("Task {} permanently failed after {} attempts: {}",
                    task.taskId, task.dispatchCount, reason);
        }
    }
}
