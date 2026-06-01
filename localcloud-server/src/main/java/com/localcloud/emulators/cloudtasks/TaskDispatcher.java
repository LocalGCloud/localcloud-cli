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
import java.util.concurrent.ConcurrentHashMap;
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

    private final ConcurrentHashMap<String, TokenBucket> rateLimiters = new ConcurrentHashMap<>();

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

                // Get or create rate limiter for this queue
                TokenBucket bucket = rateLimiters.computeIfAbsent(queueName, k -> {
                    double rate = store.getQueueMaxDispatchesPerSecond(queueName);
                    return new TokenBucket(rate);
                });

                // Update rate if it changed in the store
                double currentRate = store.getQueueMaxDispatchesPerSecond(queueName);
                bucket.updateRate(currentRate);

                List<CloudTasksStore.TaskEntry> tasks = store.getDispatchableTasks(queueName);
                for (CloudTasksStore.TaskEntry task : tasks) {
                    // Check token bucket before dispatching
                    if (!bucket.tryConsume()) {
                        // Bucket empty, skip remaining tasks for this queue
                        break;
                    }
                    dispatchTask(task);
                }
            }
        } catch (Exception e) {
            logger.error("Error in dispatch cycle", e);
        }
    }

    /**
     * Clean up rate limiter for a deleted queue.
     */
    public void removeRateLimiter(String queueName) {
        rateLimiters.remove(queueName);
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

    /**
     * Token bucket rate limiter with nanosecond-precision refill.
     * A rate of 0 means unlimited.
     */
    static class TokenBucket {
        private double tokens;
        private long lastRefillNanos;
        private volatile double ratePerSecond;

        TokenBucket(double ratePerSecond) {
            this.ratePerSecond = ratePerSecond;
            this.tokens = ratePerSecond > 0 ? ratePerSecond : Double.MAX_VALUE;
            this.lastRefillNanos = System.nanoTime();
        }

        void updateRate(double newRate) {
            this.ratePerSecond = newRate;
            if (newRate <= 0) {
                this.tokens = Double.MAX_VALUE;
            }
        }

        synchronized boolean tryConsume() {
            if (ratePerSecond <= 0) {
                return true; // unlimited
            }
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(ratePerSecond, tokens + elapsedSeconds * ratePerSecond);
            lastRefillNanos = now;
        }
    }

    private void handleFailure(CloudTasksStore.TaskEntry task, String reason) {
        // Look up the queue's configured max attempts (defaults to 100, the GCP default)
        int maxAttempts = store.getQueueMaxAttempts(task.queueName);
        // Simple retry with exponential backoff
        if (task.dispatchCount < maxAttempts) {
            // Reschedule with backoff: 2^dispatchCount seconds
            long backoffSeconds = (long) Math.pow(2, task.dispatchCount);
            task.scheduleTime = Instant.now().plusSeconds(backoffSeconds);
            task.state = "PENDING";
            logger.debug("Task {} failed ({}), retrying in {}s (attempt {}/{})",
                    task.taskId, reason, backoffSeconds, task.dispatchCount, maxAttempts);
        } else {
            task.state = "FAILED";
            logger.warn("Task {} permanently failed after {} attempts: {}",
                    task.taskId, task.dispatchCount, reason);
        }
    }
}
