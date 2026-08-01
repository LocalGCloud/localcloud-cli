package com.localcloud.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.persistence.PostgresDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Durable desired/observed state used by the polling runtime-agent protocol. */
public final class RuntimeWorkloadRepository {
    public record Record(WorkloadSpec spec, WorkloadResult result) {}

    private final PostgresDataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public RuntimeWorkloadRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    public synchronized void saveSpec(WorkloadSpec spec, WorkloadResult queued) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO runtime_workloads (workload_id, desired_state, observed_state, spec_payload, result_payload) VALUES (?, 'RUNNING', ?, ?, ?)")) {
            statement.setString(1, spec.id());
            statement.setString(2, queued.state().name());
            statement.setString(3, mapper.writeValueAsString(RuntimeAgentProtocol.WorkItem.from(spec)));
            statement.setString(4, mapper.writeValueAsString(queued));
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist runtime workload", e);
        }
    }

    public synchronized void saveResult(WorkloadResult result) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE runtime_workloads SET observed_state=?, result_payload=?, updated_at=CURRENT_TIMESTAMP WHERE workload_id=?")) {
            statement.setString(1, result.state().name());
            statement.setString(2, mapper.writeValueAsString(result));
            statement.setString(3, result.workloadId());
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist runtime result", e);
        }
    }

    public synchronized void requestCancellation(String workloadId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE runtime_workloads SET desired_state='CANCELLED', updated_at=CURRENT_TIMESTAMP WHERE workload_id=?")) {
            statement.setString(1, workloadId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist cancellation", e);
        }
    }

    public List<String> cancellations() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT workload_id FROM runtime_workloads WHERE desired_state='CANCELLED' AND observed_state NOT IN ('SUCCEEDED','FAILED','CANCELLED','INFRA_ERROR')");
             ResultSet result = statement.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (result.next()) ids.add(result.getString(1));
            return List.copyOf(ids);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list cancellations", e);
        }
    }

    public List<Record> unfinished() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT spec_payload, result_payload FROM runtime_workloads WHERE observed_state NOT IN ('SUCCEEDED','FAILED','CANCELLED','INFRA_ERROR') ORDER BY created_at");
             ResultSet result = statement.executeQuery()) {
            List<Record> records = new ArrayList<>();
            while (result.next()) records.add(new Record(
                    mapper.readValue(result.getString(1), RuntimeAgentProtocol.WorkItem.class).toSpec(),
                    mapper.readValue(result.getString(2), WorkloadResult.class)));
            return List.copyOf(records);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load unfinished workloads", e);
        }
    }

    private void createSchema() {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS runtime_workloads (workload_id VARCHAR(160) PRIMARY KEY, desired_state VARCHAR(32) NOT NULL, observed_state VARCHAR(32) NOT NULL, spec_payload TEXT NOT NULL, result_payload TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create runtime workload schema", e);
        }
    }
}
