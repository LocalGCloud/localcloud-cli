package com.localcloud.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages database schema creation and migrations for the PostgreSQL database.
 * Creates tables needed by various emulators on startup.
 */
public class SchemaManager {

    private static final Logger logger = LoggerFactory.getLogger(SchemaManager.class);

    private final PostgresDataSource dataSource;

    public SchemaManager(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Initialize the database schema, creating tables if they do not exist.
     *
     * @param defaultProjectId the default project ID to auto-insert into the projects table
     */
    public void initialize(String defaultProjectId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Projects table (must be created before service tables that reference project_id)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS projects (" +
                "    project_id VARCHAR(255) NOT NULL PRIMARY KEY," +
                "    display_name VARCHAR(255)," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Schema version tracking
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS schema_version (" +
                "    version INT NOT NULL," +
                "    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Secret Manager: secrets
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS secrets (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    secret_id VARCHAR(255) NOT NULL," +
                "    labels VARCHAR(4096) DEFAULT '{}'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, secret_id)" +
                ")"
            );

            // Secret Manager: secret_versions
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

            // Cloud Tasks: task_queues
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

            // Cloud Logging: log_entries
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

            // Cloud Monitoring: time_series
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

            // Cloud Monitoring: metric_points
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

            // Compute Engine: instances
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS compute_instances (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    zone VARCHAR(255) NOT NULL," +
                "    instance_name VARCHAR(255) NOT NULL," +
                "    machine_type VARCHAR(255) DEFAULT 'e2-medium'," +
                "    status VARCHAR(20) DEFAULT 'PROVISIONING'," +
                "    container_id VARCHAR(255)," +
                "    container_image VARCHAR(512) DEFAULT 'ubuntu:22.04'," +
                "    network_ip VARCHAR(45)," +
                "    metadata TEXT DEFAULT '{}'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, zone, instance_name)" +
                ")"
            );

            // Cloud Run: services
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloudrun_services (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location VARCHAR(255) NOT NULL," +
                "    service_id VARCHAR(255) NOT NULL," +
                "    container_image VARCHAR(512) NOT NULL," +
                "    container_port INT DEFAULT 8080," +
                "    container_id VARCHAR(255)," +
                "    host_port INT," +
                "    uri VARCHAR(1024)," +
                "    env_vars TEXT DEFAULT '{}'," +
                "    revision_count INT DEFAULT 1," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location, service_id)" +
                ")"
            );

            // Cloud Run: revisions
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloudrun_revisions (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location VARCHAR(255) NOT NULL," +
                "    service_id VARCHAR(255) NOT NULL," +
                "    revision_id VARCHAR(255) NOT NULL," +
                "    container_image VARCHAR(512) NOT NULL," +
                "    container_id VARCHAR(255)," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location, service_id, revision_id)" +
                ")"
            );

            // GKE: clusters
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS gke_clusters (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location VARCHAR(255) NOT NULL," +
                "    cluster_id VARCHAR(255) NOT NULL," +
                "    status VARCHAR(20) DEFAULT 'PROVISIONING'," +
                "    k3d_cluster_name VARCHAR(255)," +
                "    endpoint VARCHAR(512)," +
                "    cluster_version VARCHAR(20) DEFAULT '1.28'," +
                "    node_count INT DEFAULT 1," +
                "    kubeconfig TEXT," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location, cluster_id)" +
                ")"
            );

            // Cloud Tasks: cloud_tasks
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
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    CONSTRAINT fk_task_queue FOREIGN KEY (project_id, location_id, queue_name) " +
                "        REFERENCES task_queues(project_id, location_id, queue_id) ON DELETE CASCADE" +
                ")"
            );

            // Bigtable: legacy compatibility table only. The Bigtable emulator is
            // now the source of truth through gRPC admin/data APIs.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS bigtable_data (" +
                "    project_id VARCHAR(255) NOT NULL DEFAULT 'local-project'," +
                "    instance_id VARCHAR(255) NOT NULL," +
                "    table_name VARCHAR(255) NOT NULL," +
                "    row_key VARCHAR(1024) NOT NULL," +
                "    cells JSONB NOT NULL DEFAULT '{}'," +
                "    PRIMARY KEY (project_id, instance_id, table_name, row_key)" +
                ")"
            );

            // GCS bucket ownership: track which project created each bucket
            // (fake-gcs-server doesn't enforce project isolation natively)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS gcs_bucket_projects (" +
                "    bucket_name VARCHAR(255) NOT NULL PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Usage metrics: persistent cumulative request counts per project per service
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS usage_metrics (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    service_id VARCHAR(255) NOT NULL," +
                "    request_count BIGINT NOT NULL DEFAULT 0," +
                "    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, service_id)" +
                ")"
            );

            // Service routing: per-service local/remote mode configuration
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

            // Service config: persisted enable/disable state for UI toggles
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS service_config (" +
                "    service_id VARCHAR(255) PRIMARY KEY," +
                "    enabled BOOLEAN NOT NULL," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Vertex AI: local GenAI request/response history
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS vertexai_requests (" +
                "    id BIGSERIAL PRIMARY KEY," +
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vertexai_requests_project ON vertexai_requests (project_id, location_id, model_id)");

            // Cloud KMS: key hierarchy and local key material
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

            // Cloud SQL: Admin API control-plane metadata
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
                "    collation VARCHAR(128) DEFAULT ''," +
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
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS cloudsql_flags (" +
                "    database_version VARCHAR(64) NOT NULL," +
                "    flag_name VARCHAR(255) NOT NULL," +
                "    allowed_string_values TEXT DEFAULT '[]'," +
                "    PRIMARY KEY (database_version, flag_name)" +
                ")"
            );

            // Telemetry queue: unsent events persisted for retry
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS telemetry_queue (" +
                "    id SERIAL PRIMARY KEY," +
                "    event_json TEXT NOT NULL," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // Cloud Workflows: workflows
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS workflows (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL DEFAULT 'us-central1'," +
                "    workflow_id VARCHAR(255) NOT NULL," +
                "    source_contents TEXT NOT NULL," +
                "    state VARCHAR(20) DEFAULT 'ACTIVE'," +
                "    revision_id INT DEFAULT 1," +
                "    description TEXT DEFAULT ''," +
                "    labels JSONB DEFAULT '{}'," +
                "    service_account VARCHAR(500)," +
                "    call_log_level VARCHAR(30) DEFAULT 'LOG_NONE'," +
                "    execution_history_level VARCHAR(50) DEFAULT 'EXECUTION_HISTORY_BASIC'," +
                "    crypto_key_name VARCHAR(500)," +
                "    user_env_vars JSONB DEFAULT '{}'," +
                "    tags JSONB DEFAULT '{}'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, location_id, workflow_id)" +
                ")"
            );
            stmt.execute("ALTER TABLE workflows ADD COLUMN IF NOT EXISTS description TEXT DEFAULT ''");
            stmt.execute("ALTER TABLE workflows ADD COLUMN IF NOT EXISTS call_log_level VARCHAR(30) DEFAULT 'LOG_NONE'");
            stmt.execute("ALTER TABLE workflows ADD COLUMN IF NOT EXISTS execution_history_level VARCHAR(50) DEFAULT 'EXECUTION_HISTORY_BASIC'");
            stmt.execute("ALTER TABLE workflows ADD COLUMN IF NOT EXISTS crypto_key_name VARCHAR(500)");
            stmt.execute("ALTER TABLE workflows ADD COLUMN IF NOT EXISTS user_env_vars JSONB DEFAULT '{}'");
            stmt.execute("ALTER TABLE workflows ADD COLUMN IF NOT EXISTS tags JSONB DEFAULT '{}'");

            // Cloud Workflows: workflow_executions
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS workflow_executions (" +
                "    execution_id VARCHAR(255) NOT NULL PRIMARY KEY," +
                "    workflow_id VARCHAR(255) NOT NULL," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    state VARCHAR(20) DEFAULT 'QUEUED'," +
                "    argument JSONB," +
                "    result JSONB," +
                "    error JSONB," +
                "    start_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    end_time TIMESTAMP," +
                "    call_log_level VARCHAR(30) DEFAULT 'LOG_NONE'," +
                "    labels JSONB DEFAULT '{}'," +
                "    status JSONB DEFAULT '{}'," +
                "    state_error JSONB," +
                "    duration_ms BIGINT," +
                "    workflow_revision_id VARCHAR(50)" +
                ")"
            );
            stmt.execute("ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS call_log_level VARCHAR(30) DEFAULT 'LOG_NONE'");
            stmt.execute("ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS labels JSONB DEFAULT '{}'");
            stmt.execute("ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS status JSONB DEFAULT '{}'");
            stmt.execute("ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS state_error JSONB");
            stmt.execute("ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS duration_ms BIGINT");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_workflow_executions_workflow ON workflow_executions (project_id, location_id, workflow_id)");

            // Cloud Workflows: persisted execution step history
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS workflow_step_entries (" +
                "    execution_id VARCHAR(255) NOT NULL," +
                "    step_entry_id BIGSERIAL NOT NULL," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    location_id VARCHAR(255) NOT NULL," +
                "    workflow_id VARCHAR(255) NOT NULL," +
                "    step_name VARCHAR(255) NOT NULL," +
                "    step_type VARCHAR(50)," +
                "    state VARCHAR(30) DEFAULT 'SUCCEEDED'," +
                "    start_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    end_time TIMESTAMP," +
                "    duration_ms BIGINT DEFAULT 0," +
                "    entry_json JSONB DEFAULT '{}'," +
                "    PRIMARY KEY (execution_id, step_entry_id)" +
                ")"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_workflow_step_entries_execution ON workflow_step_entries (project_id, location_id, workflow_id, execution_id)");

            // Cloud Storage: storage_objects (referenced by index below)
            // Indexes for high-volume tables
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_entries_log_name ON log_entries (log_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_entries_timestamp ON log_entries (timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_log_entries_severity ON log_entries (severity)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metric_points_series_id ON metric_points (series_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metric_points_timestamp ON metric_points (end_time)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_secret_versions_secret ON secret_versions (project_id, secret_id)");

            // Workflow environment variables: per-project, per-preset key-value store
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS workflow_env_vars (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    var_name VARCHAR(255) NOT NULL," +
                "    var_value TEXT," +
                "    preset VARCHAR(50) DEFAULT 'default'," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, var_name, preset)" +
                ")"
            );

            // Workflow config: remote source connection settings and active preset
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS workflow_config (" +
                "    project_id VARCHAR(255) NOT NULL," +
                "    config_key VARCHAR(255) NOT NULL," +
                "    config_value TEXT," +
                "    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    PRIMARY KEY (project_id, config_key)" +
                ")"
            );

            // Data Mirror: sync_manifests — tracks what data was synced from production
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS sync_manifests (" +
                "    id SERIAL PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    service_id VARCHAR(50) NOT NULL," +
                "    resource_path VARCHAR(500) NOT NULL," +
                "    source_project VARCHAR(255) NOT NULL," +
                "    filters_json TEXT DEFAULT '[]'," +
                "    row_count BIGINT DEFAULT 0," +
                "    bytes_synced BIGINT DEFAULT 0," +
                "    estimated_cost DECIMAL(10,6) DEFAULT 0," +
                "    status VARCHAR(20) DEFAULT 'pending'," +
                "    error_message TEXT," +
                "    synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    UNIQUE(project_id, service_id, resource_path)" +
                ")"
            );

            // Data Mirror: sync_credentials — auth credentials for connecting to real GCP
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS sync_credentials (" +
                "    id SERIAL PRIMARY KEY," +
                "    project_id VARCHAR(255) NOT NULL," +
                "    source_project VARCHAR(255) NOT NULL," +
                "    auth_method VARCHAR(20) NOT NULL," +
                "    credential_data TEXT," +
                "    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "    UNIQUE(project_id, source_project)" +
                ")"
            );

            // Auto-insert default project (use PreparedStatement to avoid SQL injection)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO projects (project_id, display_name) " +
                    "VALUES (?, 'Default Project') " +
                    "ON CONFLICT (project_id) DO NOTHING")) {
                ps.setString(1, defaultProjectId);
                ps.executeUpdate();
            }

            logger.info("Database schema initialized");
        }
    }
}
