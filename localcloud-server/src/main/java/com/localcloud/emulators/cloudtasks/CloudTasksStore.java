package com.localcloud.emulators.cloudtasks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storage layer for the Cloud Tasks emulator.
 * Queues are persisted in PostgreSQL; tasks are persisted to cloud_tasks table
 * AND held in-memory for fast dispatch (hybrid model).
 */
public class CloudTasksStore {

    private static final Logger logger = LoggerFactory.getLogger(CloudTasksStore.class);

    private final PostgresDataSource dataSource;

    /**
     * In-memory task storage, keyed by queue full name.
     */
    private final Map<String, ConcurrentLinkedQueue<TaskEntry>> taskQueues = new ConcurrentHashMap<>();

    /**
     * Represents a task in-memory.
     */
    static class TaskEntry {
        final String taskId;
        final String queueName; // full queue resource name
        final String httpMethod;
        final String httpUrl;
        final Map<String, String> httpHeaders;
        final byte[] httpBody;
        volatile String state; // PENDING, RUNNING, COMPLETED, FAILED
        volatile Instant scheduleTime;
        volatile Instant dispatchDeadline;
        volatile int dispatchCount;
        volatile int responseCount;
        volatile Instant createTime;
        volatile Instant firstAttemptTime;
        volatile Instant lastAttemptTime;

        TaskEntry(String taskId, String queueName, String httpMethod, String httpUrl,
                  Map<String, String> httpHeaders, byte[] httpBody, Instant scheduleTime) {
            this.taskId = taskId;
            this.queueName = queueName;
            this.httpMethod = httpMethod;
            this.httpUrl = httpUrl;
            this.httpHeaders = httpHeaders;
            this.httpBody = httpBody;
            this.state = "PENDING";
            this.scheduleTime = scheduleTime != null ? scheduleTime : Instant.now();
            this.dispatchCount = 0;
            this.responseCount = 0;
            this.createTime = Instant.now();
            this.firstAttemptTime = null;
            this.lastAttemptTime = null;
        }
    }

    /**
     * Queue config holder for full configuration.
     */
    public static class QueueConfig {
        public String state = "RUNNING";
        public double maxDispatchesPerSecond = 500;
        public int maxConcurrentDispatches = 1000;
        public int maxBurstSize = 0;
        public int maxAttempts = 100;
        public String minBackoff = "0.100s";
        public String maxBackoff = "3600s";
        public int maxDoublings = 16;
        public String maxRetryDuration = "0s";
        public String httpTargetUri;
        public String httpTargetMethod;
        public String projectId;
        public String locationId;
        public String queueId;
        public Instant createdAt;
    }

    public CloudTasksStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // --- Queue operations (PostgreSQL) ---

    public void createQueue(String projectId, String locationId, String queueId) throws SQLException {
        createQueue(projectId, locationId, queueId, new QueueConfig());
    }

    public void createQueue(String projectId, String locationId, String queueId, QueueConfig config) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO task_queues (project_id, location_id, queue_id, state, max_dispatches_per_second, " +
                     "max_concurrent_dispatches, max_burst_size, max_attempts, " +
                     "min_backoff, max_backoff, max_doublings, max_retry_duration, " +
                     "http_target_uri, http_target_method, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, queueId);
            ps.setString(4, config.state != null ? config.state : "RUNNING");
            ps.setDouble(5, config.maxDispatchesPerSecond);
            ps.setInt(6, config.maxConcurrentDispatches);
            ps.setInt(7, config.maxBurstSize);
            ps.setInt(8, config.maxAttempts);
            ps.setString(9, config.minBackoff);
            ps.setString(10, config.maxBackoff);
            ps.setInt(11, config.maxDoublings);
            ps.setString(12, config.maxRetryDuration);
            ps.setString(13, config.httpTargetUri);
            ps.setString(14, config.httpTargetMethod);
            ps.executeUpdate();
            logger.debug("Created queue: projects/{}/locations/{}/queues/{}", projectId, locationId, queueId);
        }
    }

    public void updateQueue(String projectId, String locationId, String queueId, QueueConfig config) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE task_queues SET ");
        List<Object> params = new ArrayList<>();

        if (config.state != null) { appendSet(sql, params, "state", config.state); }
        if (config.maxDispatchesPerSecond > 0) { appendSet(sql, params, "max_dispatches_per_second", config.maxDispatchesPerSecond); }
        if (config.maxConcurrentDispatches > 0) { appendSet(sql, params, "max_concurrent_dispatches", config.maxConcurrentDispatches); }
        if (config.maxBurstSize >= 0) { appendSet(sql, params, "max_burst_size", config.maxBurstSize); }
        if (config.maxAttempts > 0) { appendSet(sql, params, "max_attempts", config.maxAttempts); }
        if (config.minBackoff != null) { appendSet(sql, params, "min_backoff", config.minBackoff); }
        if (config.maxBackoff != null) { appendSet(sql, params, "max_backoff", config.maxBackoff); }
        if (config.maxDoublings >= 0) { appendSet(sql, params, "max_doublings", config.maxDoublings); }
        if (config.maxRetryDuration != null) { appendSet(sql, params, "max_retry_duration", config.maxRetryDuration); }
        if (config.httpTargetUri != null) { appendSet(sql, params, "http_target_uri", config.httpTargetUri); }
        if (config.httpTargetMethod != null) { appendSet(sql, params, "http_target_method", config.httpTargetMethod); }

        if (params.isEmpty()) {
            return; // nothing to update
        }

        // Remove trailing comma
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE project_id = ? AND location_id = ? AND queue_id = ?");
        params.add(projectId);
        params.add(locationId);
        params.add(queueId);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
            logger.debug("Updated queue: projects/{}/locations/{}/queues/{}", projectId, locationId, queueId);
        }
    }

    private void appendSet(StringBuilder sql, List<Object> params, String column, Object value) {
        sql.append(column).append(" = ?, ");
        params.add(value);
    }

    public Map<String, Object> getQueue(String projectId, String locationId, String queueId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM task_queues WHERE project_id = ? AND location_id = ? AND queue_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, queueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return extractQueueRow(rs);
                }
                return null;
            }
        }
    }

    public QueueConfig getQueueConfig(String projectId, String locationId, String queueId) throws SQLException {
        Map<String, Object> row = getQueue(projectId, locationId, queueId);
        if (row == null) return null;
        return mapToQueueConfig(row);
    }

    public List<Map<String, Object>> listQueues(String projectId, String locationId) throws SQLException {
        List<Map<String, Object>> queues = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM task_queues WHERE project_id = ? AND location_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    queues.add(extractQueueRow(rs));
                }
            }
        }
        return queues;
    }

    public boolean deleteQueue(String projectId, String locationId, String queueId) throws SQLException {
        String fullName = "projects/" + projectId + "/locations/" + locationId + "/queues/" + queueId;
        taskQueues.remove(fullName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM task_queues WHERE project_id = ? AND location_id = ? AND queue_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, queueId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean pauseQueue(String projectId, String locationId, String queueId) throws SQLException {
        return updateQueueState(projectId, locationId, queueId, "PAUSED");
    }

    public boolean resumeQueue(String projectId, String locationId, String queueId) throws SQLException {
        return updateQueueState(projectId, locationId, queueId, "RUNNING");
    }

    /**
     * Purge all tasks from a queue. Deletes from DB and in-memory.
     */
    public boolean purgeQueue(String projectId, String locationId, String queueId) throws SQLException {
        String fullName = "projects/" + projectId + "/locations/" + locationId + "/queues/" + queueId;
        taskQueues.remove(fullName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM cloud_tasks WHERE project_id = ? AND location_id = ? AND queue_name = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, queueId);
            int deleted = ps.executeUpdate();
            logger.info("Purged {} tasks from queue projects/{}/locations/{}/queues/{}",
                    deleted, projectId, locationId, queueId);
            return true;
        }
    }

    private boolean updateQueueState(String projectId, String locationId, String queueId, String state) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE task_queues SET state = ? WHERE project_id = ? AND location_id = ? AND queue_id = ?")) {
            ps.setString(1, state);
            ps.setString(2, projectId);
            ps.setString(3, locationId);
            ps.setString(4, queueId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean queueExists(String projectId, String locationId, String queueId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM task_queues WHERE project_id = ? AND location_id = ? AND queue_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, queueId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public String getQueueState(String projectId, String locationId, String queueId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT state FROM task_queues WHERE project_id = ? AND location_id = ? AND queue_id = ?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, queueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("state");
                }
                return null;
            }
        }
    }

    public double getQueueMaxDispatchesPerSecond(String queueFullName) {
        try {
            String[] parts = parseQueueName(queueFullName);
            QueueConfig config = getQueueConfig(parts[0], parts[1], parts[2]);
            if (config != null) return config.maxDispatchesPerSecond;
        } catch (Exception e) {
            logger.warn("Failed to get max_dispatches_per_second for queue {}: {}", queueFullName, e.getMessage());
        }
        return 0; // default: unlimited
    }

    public int getQueueMaxAttempts(String queueFullName) {
        try {
            String[] parts = parseQueueName(queueFullName);
            QueueConfig config = getQueueConfig(parts[0], parts[1], parts[2]);
            if (config != null) return config.maxAttempts;
        } catch (Exception e) {
            logger.warn("Failed to get max_attempts for queue {}: {}", queueFullName, e.getMessage());
        }
        return 100; // GCP default
    }

    /**
     * Get full retry config for the dispatcher.
     */
    public QueueConfig getQueueRetryConfig(String projectId, String locationId, String queueId) {
        try {
            return getQueueConfig(projectId, locationId, queueId);
        } catch (SQLException e) {
            logger.warn("Failed to get retry config for queue {}/{}/{}: {}", projectId, locationId, queueId, e.getMessage());
            return new QueueConfig();
        }
    }

    /**
     * Get the queue-level HTTP target (URI and method) for dispatcher fallback.
     * Returns null if no queue-level target is configured.
     */
    public String[] getQueueHttpTarget(String queueFullName) {
        try {
            String[] parts = parseQueueName(queueFullName);
            QueueConfig config = getQueueConfig(parts[0], parts[1], parts[2]);
            if (config != null && config.httpTargetUri != null && !config.httpTargetUri.isEmpty()) {
                return new String[] { config.httpTargetUri, config.httpTargetMethod };
            }
        } catch (Exception e) {
            logger.warn("Failed to get HTTP target for queue {}: {}", queueFullName, e.getMessage());
        }
        return null;
    }

    private Map<String, Object> extractQueueRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("location_id", rs.getString("location_id"));
        row.put("queue_id", rs.getString("queue_id"));
        row.put("state", rs.getString("state"));
        row.put("max_dispatches_per_second", rs.getDouble("max_dispatches_per_second"));
        row.put("max_concurrent_dispatches", rs.getInt("max_concurrent_dispatches"));
        row.put("max_burst_size", rs.getInt("max_burst_size"));
        row.put("max_attempts", rs.getInt("max_attempts"));
        row.put("min_backoff", rs.getString("min_backoff"));
        row.put("max_backoff", rs.getString("max_backoff"));
        row.put("max_doublings", rs.getInt("max_doublings"));
        row.put("max_retry_duration", rs.getString("max_retry_duration"));
        row.put("http_target_uri", rs.getString("http_target_uri"));
        row.put("http_target_method", rs.getString("http_target_method"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    private QueueConfig mapToQueueConfig(Map<String, Object> row) {
        QueueConfig config = new QueueConfig();
        config.projectId = (String) row.get("project_id");
        config.locationId = (String) row.get("location_id");
        config.queueId = (String) row.get("queue_id");
        config.state = (String) row.get("state");
        config.maxDispatchesPerSecond = getDouble(row, "max_dispatches_per_second", 500);
        config.maxConcurrentDispatches = getInt(row, "max_concurrent_dispatches", 1000);
        config.maxBurstSize = getInt(row, "max_burst_size", 0);
        config.maxAttempts = getInt(row, "max_attempts", 100);
        config.minBackoff = getString(row, "min_backoff", "0.100s");
        config.maxBackoff = getString(row, "max_backoff", "3600s");
        config.maxDoublings = getInt(row, "max_doublings", 16);
        config.maxRetryDuration = getString(row, "max_retry_duration", "0s");
        config.httpTargetUri = (String) row.get("http_target_uri");
        config.httpTargetMethod = (String) row.get("http_target_method");
        Object created = row.get("created_at");
        if (created instanceof Timestamp) {
            config.createdAt = ((Timestamp) created).toInstant();
        }
        return config;
    }

    private String getString(Map<String, Object> row, String key, String defaultValue) {
        Object val = row.get(key);
        return val != null ? String.valueOf(val) : defaultValue;
    }

    private int getInt(Map<String, Object> row, String key, int defaultValue) {
        Object val = row.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    private double getDouble(Map<String, Object> row, String key, double defaultValue) {
        Object val = row.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultValue;
    }

    // --- Task operations (hybrid: in-memory + PostgreSQL) ---

    public TaskEntry createTask(String queueFullName, String taskId, String httpMethod,
                                 String httpUrl, Map<String, String> httpHeaders,
                                 byte[] httpBody, Instant scheduleTime) {
        return createTask(queueFullName, taskId, httpMethod, httpUrl, httpHeaders, httpBody, scheduleTime, null);
    }

    public TaskEntry createTask(String queueFullName, String taskId, String httpMethod,
                                 String httpUrl, Map<String, String> httpHeaders,
                                 byte[] httpBody, Instant scheduleTime, Instant dispatchDeadline) {
        if (taskId == null || taskId.isEmpty()) {
            taskId = UUID.randomUUID().toString();
        }

        TaskEntry entry = new TaskEntry(taskId, queueFullName, httpMethod, httpUrl,
                httpHeaders, httpBody, scheduleTime);
        if (dispatchDeadline != null) {
            entry.dispatchDeadline = dispatchDeadline;
        }

        // In-memory
        ConcurrentLinkedQueue<TaskEntry> queue =
                taskQueues.computeIfAbsent(queueFullName, k -> new ConcurrentLinkedQueue<>());
        queue.add(entry);

        // Persist to DB
        persistTask(queueFullName, entry);

        logger.debug("Created task: {}/tasks/{}", queueFullName, taskId);
        return entry;
    }

    /**
     * Persist a task to the cloud_tasks table.
     */
    private void persistTask(String queueFullName, TaskEntry entry) {
        try {
            String[] parts = parseQueueName(queueFullName);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO cloud_tasks (task_id, queue_name, project_id, location_id, " +
                         "http_method, url, headers, body, schedule_time, dispatch_deadline, " +
                         "dispatch_count, response_count, state, created_at) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, entry.taskId);
                ps.setString(2, parts[2]);
                ps.setString(3, parts[0]);
                ps.setString(4, parts[1]);
                ps.setString(5, entry.httpMethod);
                ps.setString(6, entry.httpUrl);
                ps.setString(7, entry.httpHeaders != null ? toJson(entry.httpHeaders) : null);
                ps.setBytes(8, entry.httpBody);
                ps.setTimestamp(9, entry.scheduleTime != null ? Timestamp.from(entry.scheduleTime) : null);
                ps.setTimestamp(10, entry.dispatchDeadline != null ? Timestamp.from(entry.dispatchDeadline) : null);
                ps.setInt(11, entry.dispatchCount);
                ps.setInt(12, entry.responseCount);
                ps.setString(13, entry.state);
                ps.setTimestamp(14, entry.createTime != null ? Timestamp.from(entry.createTime) : new Timestamp(System.currentTimeMillis()));
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to persist task {}: {}", entry.taskId, e.getMessage());
        }
    }

    /**
     * Update a task's DB row (state, dispatch count, timestamps).
     */
    public void updateTaskInDb(String queueFullName, TaskEntry entry) {
        try {
            String[] parts = parseQueueName(queueFullName);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE cloud_tasks SET state = ?, dispatch_count = ?, response_count = ?, " +
                         "last_attempt_time = ?, first_attempt_time = COALESCE(first_attempt_time, ?) " +
                         "WHERE task_id = ?")) {
                ps.setString(1, entry.state);
                ps.setInt(2, entry.dispatchCount);
                ps.setInt(3, entry.responseCount);
                ps.setTimestamp(4, entry.lastAttemptTime != null ? Timestamp.from(entry.lastAttemptTime) : null);
                ps.setTimestamp(5, entry.lastAttemptTime != null ? Timestamp.from(entry.lastAttemptTime) : null);
                ps.setString(6, entry.taskId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to update task {} in DB: {}", entry.taskId, e.getMessage());
        }
    }

    /**
     * Reload non-terminal tasks from DB into in-memory map on startup.
     */
    public void reloadTasks() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM cloud_tasks WHERE state IN ('PENDING', 'RUNNING')");
             ResultSet rs = ps.executeQuery()) {

            int loaded = 0;
            while (rs.next()) {
                String taskId = rs.getString("task_id");
                String queueName = rs.getString("queue_name");
                String projectId = rs.getString("project_id");
                String locationId = rs.getString("location_id");

                String queueFullName = "projects/" + projectId + "/locations/" + locationId + "/queues/" + queueName;

                String httpMethod = rs.getString("http_method");
                String url = rs.getString("url");
                Map<String, String> headers = parseHeaders(rs.getString("headers"));
                byte[] body = rs.getBytes("body");
                Timestamp scheduleTs = rs.getTimestamp("schedule_time");
                Timestamp deadlineTs = rs.getTimestamp("dispatch_deadline");

                Instant scheduleTime = scheduleTs != null ? scheduleTs.toInstant() : Instant.now();

                TaskEntry entry = new TaskEntry(taskId, queueFullName, httpMethod, url, headers, body, scheduleTime);
                entry.dispatchCount = rs.getInt("dispatch_count");
                entry.responseCount = rs.getInt("response_count");
                entry.state = rs.getString("state");
                if (deadlineTs != null) entry.dispatchDeadline = deadlineTs.toInstant();

                Timestamp createTs = rs.getTimestamp("created_at");
                if (createTs != null) entry.createTime = createTs.toInstant();

                Timestamp firstTs = rs.getTimestamp("first_attempt_time");
                if (firstTs != null) entry.firstAttemptTime = firstTs.toInstant();

                Timestamp lastTs = rs.getTimestamp("last_attempt_time");
                if (lastTs != null) entry.lastAttemptTime = lastTs.toInstant();

                ConcurrentLinkedQueue<TaskEntry> queue =
                        taskQueues.computeIfAbsent(queueFullName, k -> new ConcurrentLinkedQueue<>());
                queue.add(entry);
                loaded++;
            }
            if (loaded > 0) {
                logger.info("Reloaded {} tasks from database", loaded);
            }
        } catch (SQLException e) {
            logger.error("Failed to reload tasks from database: {}", e.getMessage(), e);
        }
    }

    public TaskEntry getTask(String queueFullName, String taskId) {
        ConcurrentLinkedQueue<TaskEntry> queue = taskQueues.get(queueFullName);
        if (queue == null) return null;

        for (TaskEntry entry : queue) {
            if (entry.taskId.equals(taskId)) {
                return entry;
            }
        }
        return null;
    }

    public List<TaskEntry> listTasks(String queueFullName) {
        ConcurrentLinkedQueue<TaskEntry> queue = taskQueues.get(queueFullName);
        if (queue == null) return List.of();
        return new ArrayList<>(queue);
    }

    public boolean deleteTask(String queueFullName, String taskId) {
        // Remove from DB
        try {
            String[] parts = parseQueueName(queueFullName);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM cloud_tasks WHERE task_id = ? AND project_id = ?")) {
                ps.setString(1, taskId);
                ps.setString(2, parts[0]);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to delete task {} from DB: {}", taskId, e.getMessage());
        }

        // Remove from memory
        ConcurrentLinkedQueue<TaskEntry> queue = taskQueues.get(queueFullName);
        if (queue == null) return false;
        return queue.removeIf(e -> e.taskId.equals(taskId));
    }

    /**
     * Get tasks that are PENDING and whose schedule_time has passed.
     * Also filters out tasks past their dispatch_deadline.
     */
    public List<TaskEntry> getDispatchableTasks(String queueFullName) {
        ConcurrentLinkedQueue<TaskEntry> queue = taskQueues.get(queueFullName);
        if (queue == null) return List.of();

        Instant now = Instant.now();
        List<TaskEntry> dispatchable = new ArrayList<>();
        for (TaskEntry entry : queue) {
            if (!"PENDING".equals(entry.state)) continue;
            if (entry.scheduleTime.isAfter(now)) continue;
            // Skip tasks past their dispatch deadline
            if (entry.dispatchDeadline != null && entry.dispatchDeadline.isBefore(now)) {
                entry.state = "FAILED";
                updateTaskInDb(queueFullName, entry);
                continue;
            }
            dispatchable.add(entry);
        }
        return dispatchable;
    }

    /**
     * Remove terminal (COMPLETED, FAILED) tasks from the in-memory queue.
     * Called periodically to prevent memory leaks in long-running emulators.
     */
    public void evictTerminalTasks(String queueFullName) {
        ConcurrentLinkedQueue<TaskEntry> queue = taskQueues.get(queueFullName);
        if (queue != null) {
            queue.removeIf(e -> "COMPLETED".equals(e.state) || "FAILED".equals(e.state));
        }
    }

    /**
     * Get all queue full names that have tasks.
     */
    public List<String> getActiveQueueNames() {
        return new ArrayList<>(taskQueues.keySet());
    }

    public void clearAll() {
        taskQueues.clear();
        try (Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM cloud_tasks");
            stmt.execute("DELETE FROM task_queues");
            logger.info("Cleared all Cloud Tasks data");
        } catch (SQLException e) {
            logger.error("Failed to clear Cloud Tasks data: {}", e.getMessage(), e);
        }
    }

    // --- Helpers ---

    static String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(escapeJson(e.getKey())).append("\":\"")
              .append(escapeJson(e.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, String> parseHeaders(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) return Collections.emptyMap();
        Map<String, String> result = new HashMap<>();
        try {
            // Simple JSON parsing for flat key-value headers
            String content = json.trim();
            if (content.startsWith("{") && content.endsWith("}")) {
                content = content.substring(1, content.length() - 1);
                String[] pairs = content.split(",");
                for (String pair : pairs) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim().replaceAll("^\"|\"$", "");
                        String value = kv[1].trim().replaceAll("^\"|\"$", "");
                        result.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse headers JSON: {}", json);
        }
        return result;
    }

    /**
     * Parse "projects/{project}/locations/{location}/queues/{queue}" into [project, location, queue].
     */
    static String[] parseQueueName(String fullName) {
        String[] segments = fullName.split("/");
        if (segments.length != 6 || !"projects".equals(segments[0]) ||
            !"locations".equals(segments[2]) || !"queues".equals(segments[4])) {
            throw new IllegalArgumentException("Invalid queue name: " + fullName);
        }
        return new String[]{segments[1], segments[3], segments[5]};
    }

    /**
     * Parse "projects/{project}/locations/{location}/queues/{queue}/tasks/{task}"
     * into [project, location, queue, task].
     */
    static String[] parseTaskName(String fullName) {
        String[] segments = fullName.split("/");
        if (segments.length != 8 || !"projects".equals(segments[0]) ||
            !"locations".equals(segments[2]) || !"queues".equals(segments[4]) ||
            !"tasks".equals(segments[6])) {
            throw new IllegalArgumentException("Invalid task name: " + fullName);
        }
        return new String[]{segments[1], segments[3], segments[5], segments[7]};
    }

    /**
     * Extract "projects/{project}/locations/{location}" -> [project, location]
     */
    static String[] parseLocationName(String fullName) {
        String[] segments = fullName.split("/");
        if (segments.length != 4 || !"projects".equals(segments[0]) || !"locations".equals(segments[2])) {
            throw new IllegalArgumentException("Invalid location name: " + fullName);
        }
        return new String[]{segments[1], segments[3]};
    }
}
