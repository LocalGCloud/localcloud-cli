-- AlloyDB: Emulator Schema + Sample Data
-- Features: clusters, instances, databases, backups, users,
--           JSONB metadata, BYTEA proto serialization,
--           FK cascade on delete, pgvector extension
--
-- Run against LocalCloud PostgreSQL. AlloyDB also creates dedicated
-- PostgreSQL databases (alloydb_<cluster_id>) with pgvector.

CREATE TABLE IF NOT EXISTS alloydb_clusters (
    project_id    VARCHAR(255) NOT NULL,
    location_id   VARCHAR(255) NOT NULL,
    cluster_id    VARCHAR(255) NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    metadata      JSONB DEFAULT '{}',
    cluster_proto BYTEA NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, cluster_id)
);

CREATE TABLE IF NOT EXISTS alloydb_instances (
    project_id    VARCHAR(255) NOT NULL,
    location_id   VARCHAR(255) NOT NULL,
    cluster_id    VARCHAR(255) NOT NULL,
    instance_id   VARCHAR(255) NOT NULL,
    instance_type VARCHAR(32) DEFAULT 'PRIMARY',
    metadata      JSONB DEFAULT '{}',
    instance_proto BYTEA NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, cluster_id, instance_id),
    FOREIGN KEY (project_id, location_id, cluster_id)
        REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alloydb_databases (
    project_id    VARCHAR(255) NOT NULL,
    location_id   VARCHAR(255) NOT NULL,
    cluster_id    VARCHAR(255) NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    physical_name VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, cluster_id, database_name),
    FOREIGN KEY (project_id, location_id, cluster_id)
        REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS alloydb_backups (
    project_id   VARCHAR(255) NOT NULL,
    location_id  VARCHAR(255) NOT NULL,
    backup_id    VARCHAR(255) NOT NULL,
    cluster_name VARCHAR(1024) NOT NULL,
    backup_type  VARCHAR(32) DEFAULT 'ON_DEMAND',
    state        VARCHAR(32) DEFAULT 'READY',
    size_bytes   BIGINT DEFAULT 0,
    metadata     JSONB DEFAULT '{}',
    backup_proto BYTEA NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, backup_id)
);

CREATE TABLE IF NOT EXISTS alloydb_users (
    project_id   VARCHAR(255) NOT NULL,
    location_id  VARCHAR(255) NOT NULL,
    cluster_id   VARCHAR(255) NOT NULL,
    user_id      VARCHAR(255) NOT NULL,
    user_type    VARCHAR(32) DEFAULT 'DB_USER',
    metadata     JSONB DEFAULT '{}',
    user_proto   BYTEA NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, cluster_id, user_id),
    FOREIGN KEY (project_id, location_id, cluster_id)
        REFERENCES alloydb_clusters(project_id, location_id, cluster_id) ON DELETE CASCADE
);

INSERT INTO alloydb_clusters (project_id, location_id, cluster_id, database_name, metadata, cluster_proto, created_at) VALUES
    ('local-project', 'us-central1', 'app-primary', 'alloydb_app_primary',
     '{"env":"production","team":"backend","pgvector":"enabled","version":"15.5","network":"projects/local-project/global/networks/default"}',
     '\x', '2024-01-15T08:00:00Z'),

    ('local-project', 'us-central1', 'analytics-cluster', 'alloydb_analytics',
     '{"env":"production","team":"data","pgvector":"enabled","version":"15.5","network":"projects/local-project/global/networks/data-vpc"}',
     '\x', '2024-03-01T10:00:00Z'),

    ('local-project', 'europe-west1', 'compliance-cluster', 'alloydb_compliance',
     '{"env":"production","team":"security","pgvector":"enabled","version":"15.5","network":"projects/local-project/global/networks/secure-vpc","encryption":"cmek","region":"eu"}',
     '\x', '2024-06-01T14:00:00Z'),

    ('local-project', 'us-central1', 'dev-cluster', 'alloydb_dev',
     '{"env":"development","team":"engineering","pgvector":"enabled","version":"15.5"}',
     '\x', '2024-09-01T09:00:00Z'),

    ('demo-project', 'us-central1', 'demo-cluster', 'alloydb_demo',
     '{"env":"demo","team":"sales","pgvector":"enabled"}',
     '\x', '2025-01-01T00:00:00Z');

INSERT INTO alloydb_instances (project_id, location_id, cluster_id, instance_id, instance_type, metadata, instance_proto, created_at) VALUES
    ('local-project', 'us-central1', 'app-primary', 'primary', 'PRIMARY',
     '{"cpu":"4","memory":"16GB","machineType":"alloydb-db-custom-4-16384"}', '\x', '2024-01-15T08:00:00Z'),
    ('local-project', 'us-central1', 'app-primary', 'read-pool-1', 'READ_POOL',
     '{"cpu":"2","memory":"8GB","machineType":"alloydb-db-custom-2-8192","nodeCount":3}', '\x', '2024-01-15T08:05:00Z'),
    ('local-project', 'us-central1', 'app-primary', 'read-pool-2', 'READ_POOL',
     '{"cpu":"4","memory":"16GB","machineType":"alloydb-db-custom-4-16384","nodeCount":2}', '\x', '2024-06-01T08:00:00Z'),

    ('local-project', 'us-central1', 'analytics-cluster', 'primary', 'PRIMARY',
     '{"cpu":"8","memory":"32GB","machineType":"alloydb-db-custom-8-32768"}', '\x', '2024-03-01T10:00:00Z'),
    ('local-project', 'us-central1', 'analytics-cluster', 'read-pool-1', 'READ_POOL',
     '{"cpu":"4","memory":"16GB","machineType":"alloydb-db-custom-4-16384","nodeCount":5}', '\x', '2024-03-01T10:05:00Z'),

    ('local-project', 'europe-west1', 'compliance-cluster', 'primary', 'PRIMARY',
     '{"cpu":"4","memory":"16GB","machineType":"alloydb-db-custom-4-16384"}', '\x', '2024-06-01T14:00:00Z'),

    ('local-project', 'us-central1', 'dev-cluster', 'primary', 'PRIMARY',
     '{"cpu":"2","memory":"8GB","machineType":"alloydb-db-custom-2-8192"}', '\x', '2024-09-01T09:00:00Z'),

    ('demo-project', 'us-central1', 'demo-cluster', 'primary', 'PRIMARY',
     '{"cpu":"1","memory":"4GB","machineType":"alloydb-db-custom-1-4096"}', '\x', '2025-01-01T00:00:00Z');

INSERT INTO alloydb_backups (project_id, location_id, backup_id, cluster_name, backup_type, state, size_bytes, metadata, backup_proto, created_at) VALUES
    ('local-project', 'us-central1', 'bkp-app-primary-001', 'projects/local-project/locations/us-central1/clusters/app-primary',
     'ON_DEMAND', 'READY', 5368709120,
     '{"type":"full","duration":"15m","tables":["customers","orders","products"]}', '\x', '2025-05-20T00:00:00Z'),
    ('local-project', 'us-central1', 'bkp-app-primary-002', 'projects/local-project/locations/us-central1/clusters/app-primary',
     'ON_DEMAND', 'RUNNING', 0,
     '{"type":"incremental","parent":"bkp-app-primary-001"}', '\x', '2025-05-20T12:00:00Z'),
    ('local-project', 'us-central1', 'bkp-analytics-001', 'projects/local-project/locations/us-central1/clusters/analytics-cluster',
     'AUTOMATED', 'READY', 10737418240,
     '{"type":"full","duration":"25m","tables":["events","sessions"]}', '\x', '2025-05-19T00:00:00Z'),
    ('local-project', 'europe-west1', 'bkp-compliance-001', 'projects/local-project/locations/europe-west1/clusters/compliance-cluster',
     'ON_DEMAND', 'READY', 2684354560,
     '{"type":"full","duration":"8m","tables":["pii_data","audit_log"]}', '\x', '2025-05-18T00:00:00Z');

INSERT INTO alloydb_users (project_id, location_id, cluster_id, user_id, user_type, metadata, user_proto, created_at) VALUES
    ('local-project', 'us-central1', 'app-primary', 'admin', 'DB_USER',
     '{"superuser":true,"roles":["rds_superuser","pgvector_user"]}', '\x', '2024-01-15T08:10:00Z'),
    ('local-project', 'us-central1', 'app-primary', 'app_user', 'DB_USER',
     '{"superuser":false,"roles":["read_write","pgvector_user"]}', '\x', '2024-01-15T08:10:00Z'),
    ('local-project', 'us-central1', 'app-primary', 'readonly_user', 'DB_USER',
     '{"superuser":false,"roles":["read_only"]}', '\x', '2024-03-01T10:10:00Z'),
    ('local-project', 'us-central1', 'analytics-cluster', 'analytics_admin', 'DB_USER',
     '{"superuser":true,"roles":["rds_superuser"]}', '\x', '2024-03-01T10:10:00Z'),
    ('local-project', 'europe-west1', 'compliance-cluster', 'compliance_admin', 'DB_USER',
     '{"superuser":true,"roles":["rds_superuser","pgvector_user"]}', '\x', '2024-06-01T14:10:00Z'),
    ('demo-project', 'us-central1', 'demo-cluster', 'demo_user', 'DB_USER',
     '{"superuser":true}', '\x', '2025-01-01T00:10:00Z');
