-- V1__initial_schema.sql
-- LocalCloud base schema (extracted from SchemaManager)
-- Flyway baseline migration

-- projects
CREATE TABLE IF NOT EXISTS projects (
        project_id VARCHAR(255) NOT NULL PRIMARY KEY,
        display_name VARCHAR(255),
        labels VARCHAR(4096) DEFAULT '{}',
        state VARCHAR(20) DEFAULT 'ACTIVE',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    );;

-- secrets
CREATE TABLE IF NOT EXISTS secrets (
        project_id VARCHAR(255) NOT NULL,
        secret_id VARCHAR(255) NOT NULL,
        labels VARCHAR(4096) DEFAULT '{}',
        replication VARCHAR(4096) DEFAULT '{}',
        expire_at TIMESTAMP,
        rotation_period BIGINT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, secret_id)
    )
    );;

-- secret_versions
CREATE TABLE IF NOT EXISTS secret_versions (
        project_id VARCHAR(255) NOT NULL,
        secret_id VARCHAR(255) NOT NULL,
        version_number INT NOT NULL,
        payload BYTEA,
        state VARCHAR(20) DEFAULT 'ENABLED',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, secret_id, version_number)
    )
    );;

-- secret_version_aliases
CREATE TABLE IF NOT EXISTS secret_version_aliases (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        secret_id VARCHAR(255) NOT NULL,
        version_number INT NOT NULL,
        alias VARCHAR(128) NOT NULL,
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (project_id, secret_id, alias)
    )
    );;

-- task_queues
CREATE TABLE IF NOT EXISTS task_queues (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        queue_id VARCHAR(255) NOT NULL,
        state VARCHAR(20) DEFAULT 'RUNNING',
        max_dispatches_per_second DOUBLE PRECISION DEFAULT 500,
        max_concurrent_dispatches INT DEFAULT 1000,
        max_attempts INT DEFAULT 100,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, queue_id)
    )
    );;

-- log_exclusion_filters
CREATE TABLE IF NOT EXISTS log_exclusion_filters (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        filter TEXT NOT NULL,
        name VARCHAR(255) NOT NULL,
        description TEXT,
        disabled BOOLEAN DEFAULT FALSE,
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (project_id, name)
    )
    );;

-- log_entries
CREATE TABLE IF NOT EXISTS log_entries (
        id VARCHAR(255) NOT NULL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL DEFAULT '',
        log_name VARCHAR(1024) NOT NULL,
        resource_type VARCHAR(255) DEFAULT '',
        resource_labels TEXT DEFAULT '{}',
        severity VARCHAR(20) DEFAULT 'DEFAULT',
        text_payload TEXT DEFAULT '',
        json_payload TEXT DEFAULT '',
        labels TEXT DEFAULT '{}',
        timestamp BIGINT DEFAULT 0,
        insert_id VARCHAR(255) DEFAULT ''
    )
    );;

-- time_series
CREATE TABLE IF NOT EXISTS time_series (
        id VARCHAR(255) NOT NULL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL DEFAULT 'local-project',
        project_name VARCHAR(1024) NOT NULL,
        metric_type VARCHAR(1024) NOT NULL,
        metric_labels TEXT DEFAULT '{}',
        resource_type VARCHAR(255) DEFAULT '',
        resource_labels TEXT DEFAULT '{}'
    )
    );;

-- metric_points
CREATE TABLE IF NOT EXISTS metric_points (
        id VARCHAR(255) NOT NULL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL DEFAULT 'local-project',
        series_id VARCHAR(255) NOT NULL,
        start_time BIGINT DEFAULT 0,
        end_time BIGINT DEFAULT 0,
        value_type VARCHAR(20) DEFAULT 'DOUBLE',
        double_value DOUBLE PRECISION DEFAULT 0,
        int_value BIGINT DEFAULT 0
    )
    );;

-- compute_instances
CREATE TABLE IF NOT EXISTS compute_instances (
        project_id VARCHAR(255) NOT NULL,
        zone VARCHAR(255) NOT NULL,
        instance_name VARCHAR(255) NOT NULL,
        machine_type VARCHAR(255) DEFAULT 'e2-medium',
        status VARCHAR(20) DEFAULT 'PROVISIONING',
        container_id VARCHAR(255),
        container_image VARCHAR(512) DEFAULT 'ubuntu:22.04',
        network_ip VARCHAR(45),
        metadata TEXT DEFAULT '{}',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, zone, instance_name)
    )
    );;

-- cloudrun_services
CREATE TABLE IF NOT EXISTS cloudrun_services (
        project_id VARCHAR(255) NOT NULL,
        location VARCHAR(255) NOT NULL,
        service_id VARCHAR(255) NOT NULL,
        container_image VARCHAR(512) NOT NULL,
        container_port INT DEFAULT 8080,
        container_id VARCHAR(255),
        host_port INT,
        uri VARCHAR(1024),
        env_vars TEXT DEFAULT '{}',
        revision_count INT DEFAULT 1,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location, service_id)
    )
    );;

-- cloudrun_revisions
CREATE TABLE IF NOT EXISTS cloudrun_revisions (
        project_id VARCHAR(255) NOT NULL,
        location VARCHAR(255) NOT NULL,
        service_id VARCHAR(255) NOT NULL,
        revision_id VARCHAR(255) NOT NULL,
        container_image VARCHAR(512) NOT NULL,
        container_id VARCHAR(255),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location, service_id, revision_id)
    )
    );;

-- gke_clusters
CREATE TABLE IF NOT EXISTS gke_clusters (
        project_id VARCHAR(255) NOT NULL,
        location VARCHAR(255) NOT NULL,
        cluster_id VARCHAR(255) NOT NULL,
        status VARCHAR(20) DEFAULT 'PROVISIONING',
        k3d_cluster_name VARCHAR(255),
        endpoint VARCHAR(512),
        cluster_version VARCHAR(20) DEFAULT '1.28',
        node_count INT DEFAULT 1,
        kubeconfig TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location, cluster_id)
    )
    );;

-- cloud_tasks
CREATE TABLE IF NOT EXISTS cloud_tasks (
        task_id VARCHAR(500) PRIMARY KEY,
        queue_name VARCHAR(255) NOT NULL,
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        http_method VARCHAR(10),
        url VARCHAR(2000),
        headers TEXT,
        body BYTEA,
        schedule_time TIMESTAMP,
        dispatch_count INT DEFAULT 0,
        response_count INT DEFAULT 0,
        state VARCHAR(20) DEFAULT 'PENDING',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT fk_task_queue FOREIGN KEY (project_id, location_id, queue_name) 
            REFERENCES task_queues(project_id, location_id, queue_id) ON DELETE CASCADE
    )
    );;

-- bigtable_data
CREATE TABLE IF NOT EXISTS bigtable_data (
        project_id VARCHAR(255) NOT NULL DEFAULT 'local-project',
        instance_id VARCHAR(255) NOT NULL,
        table_name VARCHAR(255) NOT NULL,
        row_key VARCHAR(1024) NOT NULL,
        cells JSONB NOT NULL DEFAULT '{}',
        PRIMARY KEY (project_id, instance_id, table_name, row_key)
    )
    );;

-- bigtable_instances
CREATE TABLE IF NOT EXISTS bigtable_instances (
        project_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255) NOT NULL,
        display_name VARCHAR(255),
        instance_type VARCHAR(32) DEFAULT 'PRODUCTION',
        state VARCHAR(32) DEFAULT 'READY',
        clusters_json JSONB DEFAULT '[]',
        labels_json JSONB DEFAULT '{}',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, instance_id)
    )
    );;

-- bigtable_tables
CREATE TABLE IF NOT EXISTS bigtable_tables (
        project_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255) NOT NULL,
        table_id VARCHAR(255) NOT NULL,
        column_families_json JSONB DEFAULT '[]',
        granularity VARCHAR(32) DEFAULT 'MILLIS',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, instance_id, table_id),
        FOREIGN KEY (project_id, instance_id) REFERENCES bigtable_instances(project_id, instance_id) ON DELETE CASCADE
    )
    );;

-- memorystore_instances
CREATE TABLE IF NOT EXISTS memorystore_instances (
        project_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255) NOT NULL,
        display_name VARCHAR(255),
        tier VARCHAR(32) DEFAULT 'BASIC',
        engine VARCHAR(32) DEFAULT 'REDIS',
        redis_version VARCHAR(32) DEFAULT '7_0',
        port INT DEFAULT 6379,
        memory_size_gb INT DEFAULT 1,
        state VARCHAR(32) DEFAULT 'READY',
        host VARCHAR(255) DEFAULT 'localhost',
        labels_json JSONB DEFAULT '{}',
        auth_enabled BOOLEAN DEFAULT FALSE,
        auth_password VARCHAR(128),
        persistence_mode VARCHAR(32) DEFAULT 'DISABLED',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, instance_id)
    )
    );;

-- gcs_bucket_projects
CREATE TABLE IF NOT EXISTS gcs_bucket_projects (
        bucket_name VARCHAR(255) NOT NULL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    );;

-- usage_metrics
CREATE TABLE IF NOT EXISTS usage_metrics (
        project_id VARCHAR(255) NOT NULL,
        service_id VARCHAR(255) NOT NULL,
        request_count BIGINT NOT NULL DEFAULT 0,
        last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, service_id)
    )
    );;

-- query_history
CREATE TABLE IF NOT EXISTS query_history (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        service VARCHAR(64) NOT NULL,
        sql TEXT NOT NULL,
        instance VARCHAR(255),
        database_name VARCHAR(255),
        duration_ms BIGINT DEFAULT 0,
        row_count INT DEFAULT 0,
        success BOOLEAN DEFAULT TRUE,
        error_message TEXT,
        executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    );;

-- service_routing
CREATE TABLE IF NOT EXISTS service_routing (
        project_id VARCHAR(255) NOT NULL,
        service_id VARCHAR(255) NOT NULL,
        mode VARCHAR(20) DEFAULT 'local',
        remote_project VARCHAR(255),
        remote_region VARCHAR(255),
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, service_id)
    )
    );;

-- service_config
CREATE TABLE IF NOT EXISTS service_config (
        service_id VARCHAR(255) PRIMARY KEY,
        enabled BOOLEAN NOT NULL,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    );;

-- vertexai_requests
CREATE TABLE IF NOT EXISTS vertexai_requests (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        publisher VARCHAR(255) NOT NULL,
        model_id VARCHAR(512) NOT NULL,
        method VARCHAR(64) NOT NULL,
        request_json TEXT NOT NULL,
        response_json TEXT,
        prompt_tokens INT DEFAULT 0,
        response_tokens INT DEFAULT 0,
        backend VARCHAR(64) DEFAULT 'stub',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    );;

-- kms_key_rings
CREATE TABLE IF NOT EXISTS kms_key_rings (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        key_ring_id VARCHAR(255) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, key_ring_id)
    )
    );;

-- kms_crypto_keys
CREATE TABLE IF NOT EXISTS kms_crypto_keys (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        key_ring_id VARCHAR(255) NOT NULL,
        crypto_key_id VARCHAR(255) NOT NULL,
        purpose VARCHAR(64) DEFAULT 'ENCRYPT_DECRYPT',
        algorithm VARCHAR(128) DEFAULT 'GOOGLE_SYMMETRIC_ENCRYPTION',
        primary_version INT DEFAULT 1,
        labels TEXT DEFAULT '{}',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, key_ring_id, crypto_key_id)
    )
    );;

-- kms_crypto_key_versions
CREATE TABLE IF NOT EXISTS kms_crypto_key_versions (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        key_ring_id VARCHAR(255) NOT NULL,
        crypto_key_id VARCHAR(255) NOT NULL,
        version_number INT NOT NULL,
        state VARCHAR(32) DEFAULT 'ENABLED',
        algorithm VARCHAR(128) DEFAULT 'GOOGLE_SYMMETRIC_ENCRYPTION',
        key_material BYTEA,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, key_ring_id, crypto_key_id, version_number)
    )
    );;

-- cloudsql_instances
CREATE TABLE IF NOT EXISTS cloudsql_instances (
        project_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255) NOT NULL,
        region VARCHAR(255) DEFAULT 'us-central1',
        database_version VARCHAR(64) DEFAULT 'POSTGRES_15',
        tier VARCHAR(128) DEFAULT 'db-custom-1-3840',
        state VARCHAR(32) DEFAULT 'RUNNABLE',
        backend_type VARCHAR(64) DEFAULT 'POSTGRES',
        connection_name VARCHAR(512),
        settings_json TEXT DEFAULT '{}',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, instance_id)
    )
    );;

-- cloudsql_databases
CREATE TABLE IF NOT EXISTS cloudsql_databases (
        project_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255) NOT NULL,
        database_name VARCHAR(255) NOT NULL,
        charset VARCHAR(64) DEFAULT 'UTF8',
        \"collation\" VARCHAR(128) DEFAULT '',
        physical_name VARCHAR(255),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, instance_id, database_name)
    )
    );;

-- cloudsql_users
CREATE TABLE IF NOT EXISTS cloudsql_users (
        project_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255) NOT NULL,
        user_name VARCHAR(255) NOT NULL,
        host VARCHAR(255) DEFAULT '%',
        password_hash VARCHAR(255),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, instance_id, user_name, host)
    )
    );;

-- cloudsql_operations
CREATE TABLE IF NOT EXISTS cloudsql_operations (
        operation_id VARCHAR(255) NOT NULL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255),
        operation_type VARCHAR(64) NOT NULL,
        status VARCHAR(32) DEFAULT 'DONE',
        target_link VARCHAR(1024),
        error_json TEXT DEFAULT '{}',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    );;

-- cloudsql_flags
CREATE TABLE IF NOT EXISTS cloudsql_flags (
        database_version VARCHAR(64) NOT NULL,
        flag_name VARCHAR(255) NOT NULL,
        allowed_string_values TEXT DEFAULT '[]',
        PRIMARY KEY (database_version, flag_name)
    )
    );;

-- alloydb_clusters
CREATE TABLE IF NOT EXISTS alloydb_clusters (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        cluster_id VARCHAR(255) NOT NULL,
        database_name VARCHAR(255) NOT NULL,
        metadata JSONB DEFAULT '{}',
        cluster_proto BYTEA NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, cluster_id)
    )
    );;

-- alloydb_instances
CREATE TABLE IF NOT EXISTS alloydb_instances (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        cluster_id VARCHAR(255) NOT NULL,
        instance_id VARCHAR(255) NOT NULL,
        metadata JSONB DEFAULT '{}',
        instance_proto BYTEA NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, cluster_id, instance_id),
        FOREIGN KEY (project_id, location_id, cluster_id) 
            REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
    )
    );;

-- alloydb_databases
CREATE TABLE IF NOT EXISTS alloydb_databases (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        cluster_id VARCHAR(255) NOT NULL,
        database_name VARCHAR(255) NOT NULL,
        physical_name VARCHAR(255) NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, cluster_id, database_name),
        FOREIGN KEY (project_id, location_id, cluster_id) 
            REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
    )
    );;

-- alloydb_databases
);;

-- alloydb_backups
CREATE TABLE IF NOT EXISTS alloydb_backups (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        backup_id VARCHAR(255) NOT NULL,
        cluster_name VARCHAR(1024) NOT NULL,
        metadata JSONB DEFAULT '{}',
        backup_proto BYTEA NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, backup_id)
    )
    );;

-- alloydb_users
CREATE TABLE IF NOT EXISTS alloydb_users (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        cluster_id VARCHAR(255) NOT NULL,
        user_id VARCHAR(255) NOT NULL,
        metadata JSONB DEFAULT '{}',
        user_proto BYTEA NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, cluster_id, user_id),
        FOREIGN KEY (project_id, location_id, cluster_id) 
            REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
    )
    );;

-- telemetry_queue
CREATE TABLE IF NOT EXISTS telemetry_queue (
        id SERIAL PRIMARY KEY,
        event_json TEXT NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    );;

-- workflows
CREATE TABLE IF NOT EXISTS workflows (
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL DEFAULT 'us-central1',
        workflow_id VARCHAR(255) NOT NULL,
        source_contents TEXT NOT NULL,
        state VARCHAR(20) DEFAULT 'ACTIVE',
        revision_id INT DEFAULT 1,
        description TEXT DEFAULT '',
        labels JSONB DEFAULT '{}',
        service_account VARCHAR(500),
        call_log_level VARCHAR(30) DEFAULT 'LOG_NONE',
        execution_history_level VARCHAR(50) DEFAULT 'EXECUTION_HISTORY_BASIC',
        crypto_key_name VARCHAR(500),
        user_env_vars JSONB DEFAULT '{}',
        tags JSONB DEFAULT '{}',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, location_id, workflow_id)
    )
    );;

-- workflow_executions
CREATE TABLE IF NOT EXISTS workflow_executions (
        execution_id VARCHAR(255) NOT NULL PRIMARY KEY,
        workflow_id VARCHAR(255) NOT NULL,
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        state VARCHAR(20) DEFAULT 'QUEUED',
        argument JSONB,
        result JSONB,
        error JSONB,
        start_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        end_time TIMESTAMP,
        call_log_level VARCHAR(30) DEFAULT 'LOG_NONE',
        labels JSONB DEFAULT '{}',
        status JSONB DEFAULT '{}',
        state_error JSONB,
        duration_ms BIGINT,
        workflow_revision_id VARCHAR(50)
    )
    );;

-- workflow_step_entries
CREATE TABLE IF NOT EXISTS workflow_step_entries (
        execution_id VARCHAR(255) NOT NULL,
        step_entry_id BIGSERIAL NOT NULL,
        project_id VARCHAR(255) NOT NULL,
        location_id VARCHAR(255) NOT NULL,
        workflow_id VARCHAR(255) NOT NULL,
        step_name VARCHAR(255) NOT NULL,
        step_type VARCHAR(50),
        state VARCHAR(30) DEFAULT 'SUCCEEDED',
        start_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        end_time TIMESTAMP,
        duration_ms BIGINT DEFAULT 0,
        entry_json JSONB DEFAULT '{}',
        PRIMARY KEY (execution_id, step_entry_id)
    )
    );;

-- alert_policies
CREATE TABLE IF NOT EXISTS alert_policies (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        name VARCHAR(512) NOT NULL,
        display_name VARCHAR(255),
        conditions_json TEXT DEFAULT '[]',
        combiner VARCHAR(32) DEFAULT 'OR',
        notification_channels_json TEXT DEFAULT '[]',
        documentation_json TEXT DEFAULT '{}',
        enabled BOOLEAN DEFAULT TRUE,
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (project_id, name)
    )
    );;

-- notification_channels
CREATE TABLE IF NOT EXISTS notification_channels (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        name VARCHAR(512) NOT NULL,
        type VARCHAR(64) NOT NULL,
        display_name VARCHAR(255),
        labels_json TEXT DEFAULT '{}',
        description TEXT,
        enabled BOOLEAN DEFAULT TRUE,
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (project_id, name)
    )
    );;

-- gke_node_pools
CREATE TABLE IF NOT EXISTS gke_node_pools (
        id BIGSERIAL PRIMARY KEY,
        cluster_id VARCHAR(255) NOT NULL,
        project_id VARCHAR(255) NOT NULL,
        name VARCHAR(256) NOT NULL,
        config_json TEXT DEFAULT '{}',
        initial_node_count INT DEFAULT 1,
        locations_json TEXT DEFAULT '[]',
        status VARCHAR(32) DEFAULT 'RUNNING',
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (project_id, cluster_id, name)
    )
    );;

-- workflow_env_vars
CREATE TABLE IF NOT EXISTS workflow_env_vars (
        project_id VARCHAR(255) NOT NULL,
        var_name VARCHAR(255) NOT NULL,
        var_value TEXT,
        preset VARCHAR(50) DEFAULT 'default',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, var_name, preset)
    )
    );;

-- workflow_config
CREATE TABLE IF NOT EXISTS workflow_config (
        project_id VARCHAR(255) NOT NULL,
        config_key VARCHAR(255) NOT NULL,
        config_value TEXT,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (project_id, config_key)
    )
    );;

-- sync_manifests
CREATE TABLE IF NOT EXISTS sync_manifests (
        id SERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        service_id VARCHAR(50) NOT NULL,
        resource_path VARCHAR(500) NOT NULL,
        source_project VARCHAR(255) NOT NULL,
        filters_json TEXT DEFAULT '[]',
        row_count BIGINT DEFAULT 0,
        bytes_synced BIGINT DEFAULT 0,
        estimated_cost DECIMAL(10,6) DEFAULT 0,
        status VARCHAR(20) DEFAULT 'pending',
        error_message TEXT,
        synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE(project_id, service_id, resource_path)
    )
    );;

-- sync_credentials
CREATE TABLE IF NOT EXISTS sync_credentials (
        id SERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        source_project VARCHAR(255) NOT NULL,
        auth_method VARCHAR(20) NOT NULL,
        credential_data TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE(project_id, source_project)
    )
    );;

-- billing_budgets
CREATE TABLE IF NOT EXISTS billing_budgets (
        id BIGSERIAL PRIMARY KEY,
        billing_account VARCHAR(255) NOT NULL,
        budget_id VARCHAR(255) NOT NULL,
        display_name VARCHAR(255),
        amount_json TEXT DEFAULT '{}',
        threshold_rules_json TEXT DEFAULT '[]',
        notifications_json TEXT DEFAULT '{}',
        labels_json TEXT DEFAULT '{}',
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (billing_account, budget_id)
    )
    );;

-- logging_sinks
CREATE TABLE IF NOT EXISTS logging_sinks (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        sink_id VARCHAR(255) NOT NULL,
        destination VARCHAR(1024) NOT NULL DEFAULT 'bigquery.googleapis.com',
        filter TEXT,
        writer_identity VARCHAR(512),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (project_id, sink_id)
    )
    );;

-- monitoring_alert_policies
CREATE TABLE IF NOT EXISTS monitoring_alert_policies (
        id BIGSERIAL PRIMARY KEY,
        project_id VARCHAR(255) NOT NULL,
        policy_id VARCHAR(255) NOT NULL,
        display_name VARCHAR(255) NOT NULL,
        enabled BOOLEAN DEFAULT TRUE,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (project_id, policy_id)
    )
    );;

