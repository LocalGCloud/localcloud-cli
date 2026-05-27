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

            // Query history
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS query_history (" +
                "    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    service VARCHAR(64) NOT NULL," +
                "    sql TEXT NOT NULL," +
                "    instance VARCHAR(255)," +
                "    database_name VARCHAR(255)," +
                "    duration_ms BIGINT DEFAULT 0," +
                "    row_count INT DEFAULT 0," +
                "    success BOOLEAN DEFAULT TRUE," +
                "    error_message TEXT," +
                "    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
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

            // Vertex AI
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS vertexai_requests (" +
                "    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    publisher VARCHAR(255) NOT NULL," +
                "    model_id VARCHAR(512) NOT NULL," +
                "    method VARCHAR(64) NOT NULL," +
                "    request_json TEXT NOT NULL," +
                "    response_json TEXT," +
                "    prompt_tokens INT DEFAULT 0," +
                "    response_tokens INT DEFAULT 0," +
                "    backend VARCHAR(64) DEFAULT 'stub'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Cloud KMS
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS kms_key_rings (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    key_ring_id VARCHAR(255) NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, key_ring_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS kms_crypto_keys (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    key_ring_id VARCHAR(255) NOT NULL," +
                "    crypto_key_id VARCHAR(255) NOT NULL," +
                "    purpose VARCHAR(64) DEFAULT 'ENCRYPT_DECRYPT'," +
                "    algorithm VARCHAR(128) DEFAULT 'GOOGLE_SYMMETRIC_ENCRYPTION'," +
                "    primary_version INT DEFAULT 1," +
                "    labels TEXT DEFAULT '{}'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, key_ring_id, crypto_key_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS kms_crypto_key_versions (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    key_ring_id VARCHAR(255) NOT NULL," +
                "    crypto_key_id VARCHAR(255) NOT NULL," +
                "    version_number INT NOT NULL," +
                "    state VARCHAR(32) DEFAULT 'ENABLED'," +
                "    algorithm VARCHAR(128) DEFAULT 'GOOGLE_SYMMETRIC_ENCRYPTION'," +
                "    key_material BYTEA," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, key_ring_id, crypto_key_id, version_number)" +
                ")"
            );

            // Cloud SQL
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloudsql_instances (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    instance_id VARCHAR(255) NOT NULL," +
                "    region VARCHAR(255) DEFAULT 'us-central1'," +
                "    database_version VARCHAR(64) DEFAULT 'POSTGRES_15'," +
                "    tier VARCHAR(128) DEFAULT 'db-custom-1-3840'," +
                "    state VARCHAR(32) DEFAULT 'RUNNABLE'," +
                "    backend_type VARCHAR(64) DEFAULT 'POSTGRES'," +
                "    connection_name VARCHAR(512)," +
                "    settings_json TEXT DEFAULT '{}'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, instance_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloudsql_databases (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    instance_id VARCHAR(255) NOT NULL," +
                "    database_name VARCHAR(255) NOT NULL," +
                "    charset VARCHAR(64) DEFAULT 'UTF8'," +
                "    \"collation\" VARCHAR(128) DEFAULT ''," +
                "    physical_name VARCHAR(255)," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, instance_id, database_name)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloudsql_users (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    instance_id VARCHAR(255) NOT NULL," +
                "    user_name VARCHAR(255) NOT NULL," +
                "    host VARCHAR(255) DEFAULT '%'," +
                "    password_hash VARCHAR(255)," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, instance_id, user_name, host)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloudsql_operations (" +
                "    operation_id VARCHAR(255) NOT NULL PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    instance_id VARCHAR(255)," +
                "    operation_type VARCHAR(64) NOT NULL," +
                "    status VARCHAR(32) DEFAULT 'DONE'," +
                "    target_link VARCHAR(1024)," +
                "    error_json TEXT DEFAULT '{}'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Cloud Scheduler (matches SchedulerRepository schema)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS scheduler_jobs (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    job_id VARCHAR(255) NOT NULL," +
                "    schedule VARCHAR(255) NOT NULL," +
                "    time_zone VARCHAR(255) NOT NULL," +
                "    target_config TEXT DEFAULT '{}'," +
                "    state VARCHAR(32) NOT NULL," +
                "    next_execution_time TIMESTAMP," +
                "    job_proto BYTEA NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, job_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS scheduler_executions (" +
                "    id BIGSERIAL PRIMARY KEY," +
                "    job_name VARCHAR(1024) NOT NULL," +
                "    status VARCHAR(32) NOT NULL," +
                "    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    output TEXT DEFAULT ''" +
                ")"
            );

            // Cloud Functions 2nd gen (matches CloudFunctionsRepository schema)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloud_functions (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    function_id VARCHAR(255) NOT NULL," +
                "    runtime VARCHAR(128) DEFAULT ''," +
                "    entry_point VARCHAR(255) DEFAULT ''," +
                "    build_config TEXT DEFAULT '{}'," +
                "    service_config TEXT DEFAULT '{}'," +
                "    event_trigger TEXT DEFAULT '{}'," +
                "    state VARCHAR(32) DEFAULT 'ACTIVE'," +
                "    function_proto BYTEA NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, function_id)" +
                ")"
            );

            // IAM policies (matches IAMRepository schema)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS iam_policies (" +
                "    resource_type VARCHAR(255) NOT NULL," +
                "    resource_id VARCHAR(1024) NOT NULL," +
                "    policy TEXT DEFAULT '{}'," +
                "    policy_proto BYTEA NOT NULL," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (resource_type, resource_id)" +
                ")"
            );

            // Dataproc (matches DataprocRepository schema)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dataproc_clusters (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    region VARCHAR(255) NOT NULL," +
                "    cluster_name VARCHAR(255) NOT NULL," +
                "    metadata TEXT DEFAULT '{}'," +
                "    cluster_proto BYTEA NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, region, cluster_name)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dataproc_jobs (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    region VARCHAR(255) NOT NULL," +
                "    job_id VARCHAR(255) NOT NULL," +
                "    cluster_name VARCHAR(255) NOT NULL," +
                "    status VARCHAR(64) NOT NULL," +
                "    driver_output_path VARCHAR(2048) DEFAULT ''," +
                "    job_proto BYTEA NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, region, job_id)" +
                ")"
            );

            // AlloyDB (matches AlloyDBRepository schema, with FOREIGN KEY constraints)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS alloydb_clusters (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    cluster_id VARCHAR(255) NOT NULL," +
                "    database_name VARCHAR(255) NOT NULL," +
                "    metadata TEXT DEFAULT '{}'," +
                "    cluster_proto BYTEA NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, cluster_id)" +
                ")"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS alloydb_instances (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    cluster_id VARCHAR(255) NOT NULL," +
                "    instance_id VARCHAR(255) NOT NULL," +
                "    metadata TEXT DEFAULT '{}'," +
                "    instance_proto BYTEA NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, cluster_id, instance_id)," +
                "    FOREIGN KEY (project_id, location_id, cluster_id)" +
                "        REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE" +
                ")"
            );

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize test schema", e);
        }
    }
}
