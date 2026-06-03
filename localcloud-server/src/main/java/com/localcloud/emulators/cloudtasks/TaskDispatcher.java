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
 * HTTP requests to the task target URLs. Uses queue-level retry config
 * and HTTP target fallback from the database.
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

                // Evict terminal tasks from in-memory queue to prevent memory leak
                evictTerminalTasks(queueName);

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
     * Remove COMPLETED and FAILED tasks from in-memory queue to prevent memory leak.
     */
    private void evictTerminalTasks(String queueName) {
        store.evictTerminalTasks(queueName);
    }

    /**
     * Clean up rate limiter for a deleted queue.
     */
    public void removeRateLimiter(String queueName) {
        rateLimiters.remove(queueName);
    }

    private void dispatchTask(CloudTasksStore.TaskEntry task) {
        // Resolve the HTTP URL: use task-level first, then queue-level fallback
        String httpUrl = task.httpUrl;
        String httpMethod = task.httpMethod;

        if (httpUrl == null || httpUrl.isEmpty()) {
            String[] target = store.getQueueHttpTarget(task.queueName);
            if (target != null && target[0] != null && !target[0].isEmpty()) {
                httpUrl = target[0];
                httpMethod = target[1] != null && !target[1].isEmpty() ? target[1] : httpMethod;
                logger.debug("Task {} using queue-level HTTP target: {} {}", task.taskId, httpMethod, httpUrl);
            } else {
                logger.warn("Task {} has no HTTP URL and queue has no HTTP target, marking as FAILED", task.taskId);
                task.state = "FAILED";
                store.updateTaskInDb(task.queueName, task);
                return;
            }
        }

        // Check dispatch deadline
        if (task.dispatchDeadline != null && Instant.now().isAfter(task.dispatchDeadline)) {
            logger.warn("Task {} dispatch deadline has passed, marking as FAILED", task.taskId);
            task.state = "FAILED";
            store.updateTaskInDb(task.queueName, task);
            return;
        }

        task.state = "RUNNING";
        task.dispatchCount++;
        task.lastAttemptTime = Instant.now();
        if (task.firstAttemptTime == null) {
            task.firstAttemptTime = Instant.now();
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(httpUrl))
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

            String method = httpMethod != null ? httpMethod.toUpperCase() : "POST";
            reqBuilder = switch (method) {
                case "GET" -> reqBuilder.GET();
                case "PUT" -> reqBuilder.PUT(bodyPublisher);
                case "DELETE" -> reqBuilder.DELETE();
                case "PATCH" -> reqBuilder.method("PATCH", bodyPublisher);
                case "HEAD" -> reqBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                case "OPTIONS" -> reqBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                default -> reqBuilder.POST(bodyPublisher);
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

        // Persist task state update to DB
        store.updateTaskInDb(task.queueName, task);
    }

    /**
     * Handle retry logic using the queue's configured retry settings.
     */
    private void handleFailure(CloudTasksStore.TaskEntry task, String reason) {
        try {
            String[] parts = CloudTasksStore.parseQueueName(task.queueName);
            CloudTasksStore.QueueConfig config = store.getQueueRetryConfig(parts[0], parts[1], parts[2]);

            if (config == null) {
                config = new CloudTasksStore.QueueConfig();
            }

            int maxAttempts = config.maxAttempts;

            // Check max_retry_duration
            if (config.maxRetryDuration != null && !"0s".equals(config.maxRetryDuration)) {
                double maxDurationSecs = parseDurationSeconds(config.maxRetryDuration);
                if (maxDurationSecs > 0 && task.firstAttemptTime != null) {
                    long elapsedSecs = Duration.between(task.firstAttemptTime, Instant.now()).getSeconds();
                    if (elapsedSecs >= maxDurationSecs) {
                        task.state = "FAILED";
                        logger.warn("Task {} retry duration exceeded ({}s >= {}s): {}",
                                task.taskId, elapsedSecs, maxDurationSecs, reason);
                        return;
                    }
                }
            }

            if (task.dispatchCount < maxAttempts) {
                // Calculate backoff using queue retry config
                long backoffSeconds = calculateBackoff(task.dispatchCount, config);
                task.scheduleTime = Instant.now().plusSeconds(backoffSeconds);
                task.state = "PENDING";
                logger.debug("Task {} failed ({}), retrying in {}s (attempt {}/{})",
                        task.taskId, reason, backoffSeconds, task.dispatchCount, maxAttempts);
            } else {
                task.state = "FAILED";
                logger.warn("Task {} permanently failed after {} attempts: {}",
                        task.taskId, task.dispatchCount, reason);
            }
        } catch (Exception e) {
            // Fallback: use conservative defaults if DB is unavailable
            if (task.dispatchCount < 3) {
                long backoffSeconds = (long) Math.pow(2, task.dispatchCount);
                task.scheduleTime = Instant.now().plusSeconds(backoffSeconds);
                task.state = "PENDING";
            } else {
                task.state = "FAILED";
            }
            logger.warn("Failed to read queue retry config, using fallback for task {}: {}", task.taskId, e.getMessage());
        }
    }

    /**
     * Calculate backoff duration using queue retry config.
     * Starts at min_backoff, doubles max_doublings times, then grows linearly to max_backoff.
     */
    private long calculateBackoff(int attemptNumber, CloudTasksStore.QueueConfig config) {
        double minSecs = parseDurationSeconds(config.minBackoff);
        double maxSecs = parseDurationSeconds(config.maxBackoff);

        if (minSecs <= 0) minSecs = 0.1; // default 100ms
        if (maxSecs <= 0) maxSecs = 3600; // default 1 hour

        int maxDoublings = config.maxDoublings > 0 ? config.maxDoublings : 16;

        // Attempt 0 = first retry, starts at minBackoff
        double backoff;
        if (attemptNumber <= maxDoublings) {
            backoff = minSecs * Math.pow(2, attemptNumber - 1);
        } else {
            backoff = minSecs * Math.pow(2, maxDoublings);
            backoff += (attemptNumber - maxDoublings) * (maxSecs / (maxDoublings + 1));
        }

        // Cap at max_backoff
        if (backoff > maxSecs) {
            backoff = maxSecs;
        }

        return Math.round(backoff);
    }

    /**
     * Parse a duration string like "0.100s" or "3600s" to seconds as double.
     */
    private double parseDurationSeconds(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            String numericPart = s.replace("s", "");
            return Double.parseDouble(numericPart);
        } catch (NumberFormatException e) {
            return 0;
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

        synchronized void updateRate(double newRate) {
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
}
