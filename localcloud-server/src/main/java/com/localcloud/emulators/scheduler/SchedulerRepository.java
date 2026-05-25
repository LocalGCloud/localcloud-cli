package com.localcloud.emulators.scheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.cloud.scheduler.v1.Job;
import com.localcloud.persistence.PostgresDataSource;

public class SchedulerRepository {
    public record Execution(String jobName, String status, Instant executedAt, String output) {}

    private final PostgresDataSource dataSource;

    public SchedulerRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    private void createSchema() {
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            String jsonType = conn.getMetaData().getURL().contains(":h2:") ? "TEXT" : "JSONB";
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS scheduler_jobs (
                        project_id VARCHAR(255) NOT NULL,
                        location_id VARCHAR(255) NOT NULL,
                        job_id VARCHAR(255) NOT NULL,
                        schedule VARCHAR(255) NOT NULL,
                        time_zone VARCHAR(255) NOT NULL,
                        target_config %s DEFAULT '{}',
                        state VARCHAR(32) NOT NULL,
                        next_execution_time TIMESTAMP,
                        job_proto BYTEA NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location_id, job_id)
                    )
                    """.formatted(jsonType));
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS scheduler_executions (
                        id BIGSERIAL PRIMARY KEY,
                        job_name VARCHAR(1024) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        output TEXT DEFAULT ''
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize Scheduler schema", e);
        }
    }

    public boolean exists(String projectId, String locationId, String jobId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM scheduler_jobs WHERE project_id=? AND location_id=? AND job_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void create(String projectId, String locationId, String jobId, Job job, Instant nextExecutionTime)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO scheduler_jobs
                     (project_id, location_id, job_id, schedule, time_zone, state, next_execution_time, job_proto)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, jobId);
            ps.setString(4, job.getSchedule());
            ps.setString(5, job.getTimeZone());
            ps.setString(6, job.getState().name());
            ps.setTimestamp(7, nextExecutionTime == null ? null : Timestamp.from(nextExecutionTime));
            ps.setBytes(8, job.toByteArray());
            ps.executeUpdate();
        }
    }

    public Job get(String projectId, String locationId, String jobId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT job_proto FROM scheduler_jobs WHERE project_id=? AND location_id=? AND job_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? parse(rs.getBytes("job_proto")) : null;
            }
        }
    }

    public List<Job> list(String projectId, String locationId) throws SQLException {
        List<Job> jobs = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT job_proto FROM scheduler_jobs
                     WHERE project_id=? AND location_id=?
                     ORDER BY job_id
                     """)) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) jobs.add(parse(rs.getBytes("job_proto")));
            }
        }
        return jobs;
    }

    public boolean update(String projectId, String locationId, String jobId, Job job, Instant nextExecutionTime)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE scheduler_jobs
                     SET schedule=?, time_zone=?, state=?, next_execution_time=?, job_proto=?, updated_at=CURRENT_TIMESTAMP
                     WHERE project_id=? AND location_id=? AND job_id=?
                     """)) {
            ps.setString(1, job.getSchedule());
            ps.setString(2, job.getTimeZone());
            ps.setString(3, job.getState().name());
            ps.setTimestamp(4, nextExecutionTime == null ? null : Timestamp.from(nextExecutionTime));
            ps.setBytes(5, job.toByteArray());
            ps.setString(6, projectId);
            ps.setString(7, locationId);
            ps.setString(8, jobId);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean delete(String projectId, String locationId, String jobId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM scheduler_jobs WHERE project_id=? AND location_id=? AND job_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, locationId);
            ps.setString(3, jobId);
            return ps.executeUpdate() > 0;
        }
    }

    public void recordExecution(String jobName, String status, String output) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scheduler_executions (job_name, status, output) VALUES (?, ?, ?)")) {
            ps.setString(1, jobName);
            ps.setString(2, status);
            ps.setString(3, output);
            ps.executeUpdate();
        }
    }

    private static Job parse(byte[] bytes) throws SQLException {
        try {
            return Job.parseFrom(bytes);
        } catch (Exception e) {
            throw new SQLException("Failed to parse stored Scheduler job", e);
        }
    }
}
