package com.localcloud.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.persistence.PostgresDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public final class MigrationRepository {
    private final PostgresDataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public MigrationRepository(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    public MigrationSuite saveSuite(MigrationSuite requested) {
        int revision = nextRevision(requested.id());
        MigrationSuite suite = new MigrationSuite(requested.id(), revision, requested.name(), requested.baselineProfile(),
                requested.targetProfiles(), requested.capability(), requested.command(), requested.environment(),
                requested.fixtureYaml(), requested.mounts(), requested.outputPaths(), requested.assertions(),
                requested.performanceTolerance(), requested.timeoutSeconds());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO migration_suites (suite_id, revision, payload) VALUES (?, ?, ?)")) {
            statement.setString(1, suite.id());
            statement.setInt(2, suite.revision());
            statement.setString(3, mapper.writeValueAsString(suite));
            statement.executeUpdate();
            return suite;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save migration suite", e);
        }
    }

    public MigrationSuite getSuite(String id, Integer revision) {
        String sql = revision == null
                ? "SELECT payload FROM migration_suites WHERE suite_id=? ORDER BY revision DESC LIMIT 1"
                : "SELECT payload FROM migration_suites WHERE suite_id=? AND revision=?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            if (revision != null) statement.setInt(2, revision);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapper.readValue(result.getString(1), MigrationSuite.class) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read migration suite", e);
        }
    }

    public List<MigrationSuite> listSuites() {
        String sql = "SELECT s.payload FROM migration_suites s JOIN (SELECT suite_id, MAX(revision) revision FROM migration_suites GROUP BY suite_id) latest ON latest.suite_id=s.suite_id AND latest.revision=s.revision ORDER BY s.suite_id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<MigrationSuite> suites = new ArrayList<>();
            while (result.next()) suites.add(mapper.readValue(result.getString(1), MigrationSuite.class));
            return List.copyOf(suites);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list migration suites", e);
        }
    }

    public synchronized void saveRun(MigrationReport report) {
        String payload;
        try {
            payload = mapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize migration run", e);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE migration_runs SET state=?, payload=?, updated_at=CURRENT_TIMESTAMP WHERE run_id=?")) {
            update.setString(1, report.state().name());
            update.setString(2, payload);
            update.setString(3, report.runId());
            if (update.executeUpdate() > 0) return;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO migration_runs (run_id, suite_revision, state, payload) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, report.runId());
                insert.setString(2, report.suiteRevision());
                insert.setString(3, report.state().name());
                insert.setString(4, payload);
                insert.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save migration run", e);
        }
    }

    public MigrationReport getRun(String runId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT payload FROM migration_runs WHERE run_id=?")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapper.readValue(result.getString(1), MigrationReport.class) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read migration run", e);
        }
    }

    public List<MigrationReport> listRuns() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload FROM migration_runs ORDER BY updated_at DESC LIMIT 100");
             ResultSet result = statement.executeQuery()) {
            List<MigrationReport> runs = new ArrayList<>();
            while (result.next()) runs.add(mapper.readValue(result.getString(1), MigrationReport.class));
            return List.copyOf(runs);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list migration runs", e);
        }
    }

    private int nextRevision(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(revision), 0) + 1 FROM migration_suites WHERE suite_id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to allocate suite revision", e);
        }
    }

    private void createSchema() {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS migration_suites (suite_id VARCHAR(128) NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (suite_id, revision))");
            statement.execute("CREATE TABLE IF NOT EXISTS migration_runs (run_id VARCHAR(128) PRIMARY KEY, suite_revision VARCHAR(192) NOT NULL, state VARCHAR(32) NOT NULL, payload TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create migration schema", e);
        }
    }
}
