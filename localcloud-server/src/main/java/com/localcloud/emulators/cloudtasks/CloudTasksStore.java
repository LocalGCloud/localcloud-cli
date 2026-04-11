package com.localcloud.emulators.cloudtasks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
 * Queues are persisted in PostgreSQL; tasks are held in-memory for fast dispatch.
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
        volatile int dispatchCount;
        volatile int responseCount;
        volatile Instant createTime;
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
            this.lastAttemptTime = null;
        }
    }

    public CloudTasksStore(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // --- Queue operations (PostgreSQL) ---

    public void createQueue(String projectId, String locationId, String queueId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO task_queues (project_id, location_id, queue_id, state, max_dispatches_per_second, " +
                     "max_concurrent_dispatches, max_attempts, created_at) " +
                     "VALUES (?, ?, ?, 'RUNNING', 500, 1000, 100, CURRENT_TIMESTAMP)")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, queueId);
            ps.executeUpdate();
            logger.debug("Created queue: projects/{}/locations/{}/queues/{}", projectId, locationId, queueId);
        }
    }

    public Map<String, Object> getQueue(String projectId, String locationId, String queueId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, queue_id, state, max_dispatches_per_second, " +
                     "max_concurrent_dispatches, max_attempts, created_at " +
                     "FROM task_queues WHERE project_id = ? AND location_id = ? AND queue_id = ?")) {
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

    public List<Map<String, Object>> listQueues(String projectId, String locationId) throws SQLException {
        List<Map<String, Object>> queues = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT project_id, location_id, queue_id, state, max_dispatches_per_second, " +
                     "max_concurrent_dispatches, max_attempts, created_at " +
                     "FROM task_queues WHERE project_id = ? AND location_id = ?")) {
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

    public int getQueueMaxAttempts(String queueFullName) {
        try {
            String[] parts = parseQueueName(queueFullName);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT max_attempts FROM task_queues WHERE project_id = ? AND location_id = ? AND queue_id = ?")) {
                ps.setString(1, parts[0]);
                ps.setString(2, parts[1]);
                ps.setString(3, parts[2]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("max_attempts");
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get max_attempts for queue {}: {}", queueFullName, e.getMessage());
        }
        return 100; // GCP default
    }

    private Map<String, Object> extractQueueRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", rs.getString("project_id"));
        row.put("location_id", rs.getString("location_id"));
        row.put("queue_id", rs.getString("queue_id"));
        row.put("state", rs.getString("state"));
        row.put("max_dispatches_per_second", rs.getDouble("max_dispatches_per_second"));
        row.put("max_concurrent_dispatches", rs.getInt("max_concurrent_dispatches"));
        row.put("max_attempts", rs.getInt("max_attempts"));
        row.put("created_at", rs.getTimestamp("created_at"));
        return row;
    }

    // --- Task operations (in-memory) ---

    public TaskEntry createTask(String queueFullName, String taskId, String httpMethod,
                                 String httpUrl, Map<String, String> httpHeaders,
                                 byte[] httpBody, Instant scheduleTime) {
        if (taskId == null || taskId.isEmpty()) {
            taskId = UUID.randomUUID().toString();
        }

        TaskEntry entry = new TaskEntry(taskId, queueFullName, httpMethod, httpUrl,
                httpHeaders, httpBody, scheduleTime);

        ConcurrentLinkedQueue<TaskEntry> queue =
                taskQueues.computeIfAbsent(queueFullName, k -> new ConcurrentLinkedQueue<>());
        queue.add(entry);
        logger.debug("Created task: {}/tasks/{}", queueFullName, taskId);
        return entry;
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
        ConcurrentLinkedQueue<TaskEntry> queue = taskQueues.get(queueFullName);
        if (queue == null) return false;
        return queue.removeIf(e -> e.taskId.equals(taskId));
    }

    /**
     * Get tasks that are PENDING and whose schedule_time has passed.
     */
    public List<TaskEntry> getDispatchableTasks(String queueFullName) {
        ConcurrentLinkedQueue<TaskEntry> queue = taskQueues.get(queueFullName);
        if (queue == null) return List.of();

        Instant now = Instant.now();
        List<TaskEntry> dispatchable = new ArrayList<>();
        for (TaskEntry entry : queue) {
            if ("PENDING".equals(entry.state) && !entry.scheduleTime.isAfter(now)) {
                dispatchable.add(entry);
            }
        }
        return dispatchable;
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
            stmt.execute("DELETE FROM task_queues");
            logger.info("Cleared all Cloud Tasks data");
        } catch (SQLException e) {
            logger.error("Failed to clear Cloud Tasks data: {}", e.getMessage(), e);
        }
    }

    // --- Helpers ---

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
