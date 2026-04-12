package com.localcloud.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.localcloud.persistence.PostgresDataSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test utility that creates a mocked {@link PostgresDataSource} backed by an
 * H2 in-memory database in PostgreSQL compatibility mode.
 *
 * <p>Each call to {@link #create(String)} returns an isolated database with the
 * schema tables needed by facade emulators pre-initialized.
 */
public final class TestDataSource {

    private final String jdbcUrl;
    private final PostgresDataSource mockDataSource;

    private TestDataSource(String dbName) {
        this.jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        this.mockDataSource = mock(PostgresDataSource.class);
        try {
            when(mockDataSource.getConnection()).thenAnswer(inv ->
                    DriverManager.getConnection(jdbcUrl));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        initSchema();
    }

    /**
     * Create an isolated test data source.
     *
     * @param dbName unique name for the in-memory database
     */
    public static TestDataSource create(String dbName) {
        return new TestDataSource(dbName);
    }

    /**
     * Return the mocked {@link PostgresDataSource} that can be passed to emulator constructors.
     */
    public PostgresDataSource getDataSource() {
        return mockDataSource;
    }

    /**
     * Get a direct JDBC connection to the H2 database (for test assertions).
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * Drop all objects and release the database.
     */
    public void close() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        } catch (SQLException e) {
            // ignore
        }
    }

    private void initSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Secret Manager
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS secrets (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    secret_id VARCHAR(255) NOT NULL," +
                "    labels VARCHAR(4096) DEFAULT '{}'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, secret_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS secret_versions (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    secret_id VARCHAR(255) NOT NULL," +
                "    version_number INT NOT NULL," +
                "    payload BYTEA," +
                "    state VARCHAR(20) DEFAULT 'ENABLED'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, secret_id, version_number)" +
                ")"
            );

            // Cloud Tasks
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS task_queues (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    queue_id VARCHAR(255) NOT NULL," +
                "    state VARCHAR(20) DEFAULT 'RUNNING'," +
                "    max_dispatches_per_second DOUBLE PRECISION DEFAULT 500," +
                "    max_concurrent_dispatches INT DEFAULT 1000," +
                "    max_attempts INT DEFAULT 100," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, queue_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloud_tasks (" +
                "    task_id VARCHAR(500) PRIMARY KEY," +
                "    queue_name VARCHAR(255) NOT NULL," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    http_method VARCHAR(10)," +
                "    url VARCHAR(2000)," +
                "    headers TEXT," +
                "    body BYTEA," +
                "    schedule_time TIMESTAMP," +
                "    dispatch_count INT DEFAULT 0," +
                "    response_count INT DEFAULT 0," +
                "    state VARCHAR(20) DEFAULT 'PENDING'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Cloud Logging
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS log_entries (" +
                "    id VARCHAR(255) NOT NULL PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL DEFAULT ''," +
                "    log_name VARCHAR(1024) NOT NULL," +
                "    resource_type VARCHAR(255) DEFAULT ''," +
                "    resource_labels TEXT DEFAULT '{}'," +
                "    severity VARCHAR(20) DEFAULT 'DEFAULT'," +
                "    text_payload TEXT DEFAULT ''," +
                "    json_payload TEXT DEFAULT ''," +
                "    labels TEXT DEFAULT '{}'," +
                "    timestamp BIGINT DEFAULT 0," +
                "    insert_id VARCHAR(255) DEFAULT ''" +
                ")"
            );

            // Cloud Monitoring
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS time_series (" +
                "    id VARCHAR(255) NOT NULL PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL DEFAULT 'local-project'," +
                "    project_name VARCHAR(1024) NOT NULL," +
                "    metric_type VARCHAR(1024) NOT NULL," +
                "    metric_labels TEXT DEFAULT '{}'," +
                "    resource_type VARCHAR(255) DEFAULT ''," +
                "    resource_labels TEXT DEFAULT '{}'" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS metric_points (" +
                "    id VARCHAR(255) NOT NULL PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL DEFAULT 'local-project'," +
                "    series_id VARCHAR(255) NOT NULL," +
                "    start_time BIGINT DEFAULT 0," +
                "    end_time BIGINT DEFAULT 0," +
                "    value_type VARCHAR(20) DEFAULT 'DOUBLE'," +
                "    double_value DOUBLE PRECISION DEFAULT 0," +
                "    int_value BIGINT DEFAULT 0" +
                ")"
            );

            // Service routing
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS service_routing (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    service_id VARCHAR(255) NOT NULL," +
                "    mode VARCHAR(20) DEFAULT 'local'," +
                "    remote_project VARCHAR(255)," +
                "    remote_region VARCHAR(255)," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, service_id)" +
                ")"
            );

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize test schema", e);
        }
    }
}
