package com.localcloud.persistence;

import java.sql.Connection;
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
     */
    public void initialize() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

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
                "    log_name VARCHAR(1024) NOT NULL," +
                "    resource_type VARCHAR(255) DEFAULT ''," +
                "    resource_labels TEXT DEFAULT '{}'," +
                "    severity VARCHAR(20) DEFAULT 'DEFAULT'," +
                "    text_payload TEXT DEFAULT ''," +
                "    json_payload TEXT DEFAULT ''," +
                "    timestamp BIGINT DEFAULT 0," +
                "    insert_id VARCHAR(255) DEFAULT ''" +
                ")"
            );

            // Cloud Monitoring: time_series
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS time_series (" +
                "    id VARCHAR(255) NOT NULL PRIMARY KEY," +
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

            logger.info("Database schema initialized");
        }
    }
}
