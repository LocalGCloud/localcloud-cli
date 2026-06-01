-- Cloud SQL: Emulator Schema + Sample Data
-- Features: instances, databases, users, flags, operations,
--           multiple database engines (PostgreSQL, MySQL), settings

CREATE TABLE IF NOT EXISTS cloudsql_instances (
    project_id      VARCHAR(255) NOT NULL,
    instance_id     VARCHAR(255) NOT NULL,
    region          VARCHAR(255) DEFAULT 'us-central1',
    database_version VARCHAR(64) DEFAULT 'POSTGRES_15',
    tier            VARCHAR(128) DEFAULT 'db-custom-1-3840',
    state           VARCHAR(32) DEFAULT 'RUNNABLE',
    backend_type    VARCHAR(64) DEFAULT 'POSTGRES',
    connection_name VARCHAR(512),
    disk_size_gb    INT DEFAULT 100,
    disk_type       VARCHAR(32) DEFAULT 'SSD',
    disk_autoresize BOOLEAN DEFAULT TRUE,
    settings_json   JSONB DEFAULT '{}',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, instance_id)
);

CREATE TABLE IF NOT EXISTS cloudsql_databases (
    project_id    VARCHAR(255) NOT NULL,
    instance_id   VARCHAR(255) NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    charset       VARCHAR(64) DEFAULT 'UTF8',
    collation     VARCHAR(128) DEFAULT '',
    physical_name VARCHAR(255),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, instance_id, database_name)
);

CREATE TABLE IF NOT EXISTS cloudsql_users (
    project_id    VARCHAR(255) NOT NULL,
    instance_id   VARCHAR(255) NOT NULL,
    user_name     VARCHAR(255) NOT NULL,
    host          VARCHAR(255) DEFAULT '%',
    password_hash VARCHAR(255),
    user_type     VARCHAR(32) DEFAULT 'BUILT_IN',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, instance_id, user_name, host)
);

CREATE TABLE IF NOT EXISTS cloudsql_operations (
    operation_id  VARCHAR(255) NOT NULL PRIMARY KEY,
    project_id    VARCHAR(255) NOT NULL,
    instance_id   VARCHAR(255),
    operation_type VARCHAR(64) NOT NULL,
    status        VARCHAR(32) DEFAULT 'DONE',
    target_link   VARCHAR(1024),
    error_json    JSONB DEFAULT '{}',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cloudsql_flags (
    database_version     VARCHAR(64) NOT NULL,
    flag_name            VARCHAR(255) NOT NULL,
    allowed_string_values TEXT DEFAULT '[]',
    PRIMARY KEY (database_version, flag_name)
);

INSERT INTO cloudsql_instances (project_id, instance_id, region, database_version, tier, state, backend_type, connection_name, disk_size_gb, disk_type, disk_autoresize, settings_json, created_at) VALUES
    ('local-project', 'local-postgres', 'us-central1', 'POSTGRES_15', 'db-custom-4-8192', 'RUNNABLE', 'POSTGRES',
     'local-project:us-central1:local-postgres', 100, 'SSD', TRUE,
     '{"autoVacuum":"on","maxConnections":200,"sharedBuffers":"2GB","effectiveCacheSize":"6GB","workMem":"64MB","maintenanceWorkMem":"256MB"}',
     '2024-01-15T08:00:00Z'),

    ('local-project', 'analytics-postgres', 'us-central1', 'POSTGRES_15', 'db-custom-8-16384', 'RUNNABLE', 'POSTGRES',
     'local-project:us-central1:analytics-postgres', 500, 'SSD', TRUE,
     '{"autoVacuum":"on","maxConnections":500,"sharedBuffers":"4GB","effectiveCacheSize":"12GB","workMem":"128MB","maintenanceWorkMem":"512MB","pgBouncer":"enabled"}',
     '2024-03-01T10:00:00Z'),

    ('local-project', 'local-mysql', 'us-central1', 'MYSQL_8_0', 'db-custom-2-4096', 'RUNNABLE', 'MYSQL',
     'local-project:us-central1:local-mysql', 50, 'SSD', TRUE,
     '{"innodbBufferPoolSize":"2GB","maxConnections":150,"queryCacheType":"OFF","innodbLogFileSize":"512MB"}',
     '2024-06-01T14:00:00Z'),

    ('local-project', 'wordpress-mysql', 'us-east1', 'MYSQL_8_0', 'db-custom-1-3840', 'RUNNABLE', 'MYSQL',
     'local-project:us-east1:wordpress-mysql', 20, 'SSD', TRUE,
     '{"innodbBufferPoolSize":"1GB","maxConnections":100}',
     '2024-09-01T09:00:00Z'),

    ('local-project', 'datawarehouse-postgres', 'us-central1', 'POSTGRES_16', 'db-custom-32-131072', 'STOPPED', 'POSTGRES',
     'local-project:us-central1:datawarehouse-postgres', 2000, 'SSD', FALSE,
     '{"autoVacuum":"on","maxConnections":1000,"sharedBuffers":"16GB","effectiveCacheSize":"48GB"}',
     '2024-04-15T09:00:00Z'),

    ('demo-project', 'demo-postgres', 'us-central1', 'POSTGRES_15', 'db-custom-1-3840', 'RUNNABLE', 'POSTGRES',
     'local-project:us-central1:demo-postgres', 20, 'SSD', TRUE,
     '{}', '2025-01-01T00:00:00Z');

INSERT INTO cloudsql_databases (project_id, instance_id, database_name, charset, collation, created_at) VALUES
    ('local-project', 'local-postgres', 'app_db', 'UTF8', 'en_US.UTF-8', '2024-01-15T08:05:00Z'),
    ('local-project', 'local-postgres', 'analytics_db', 'UTF8', 'en_US.UTF-8', '2024-03-01T10:05:00Z'),
    ('local-project', 'local-postgres', 'sessions_db', 'UTF8', 'en_US.UTF-8', '2024-06-01T08:00:00Z'),
    ('local-project', 'analytics-postgres', 'bi_reports', 'UTF8', 'en_US.UTF-8', '2024-03-01T10:05:00Z'),
    ('local-project', 'analytics-postgres', 'user_analytics', 'UTF8', 'en_US.UTF-8', '2024-06-01T08:00:00Z'),
    ('local-project', 'local-mysql', 'wordpress_db', 'utf8mb4', 'utf8mb4_unicode_ci', '2024-06-01T14:05:00Z'),
    ('local-project', 'local-mysql', 'ecommerce_db', 'utf8mb4', 'utf8mb4_general_ci', '2024-09-01T09:00:00Z'),
    ('local-project', 'wordpress-mysql', 'wp_blog', 'utf8mb4', 'utf8mb4_unicode_ci', '2024-09-01T09:10:00Z'),
    ('demo-project', 'demo-postgres', 'demo_app', 'UTF8', 'en_US.UTF-8', '2025-01-01T00:05:00Z');

INSERT INTO cloudsql_users (project_id, instance_id, user_name, host, password_hash, user_type, created_at) VALUES
    ('local-project', 'local-postgres', 'admin', '%', 'sha256:...', 'BUILT_IN', '2024-01-15T08:10:00Z'),
    ('local-project', 'local-postgres', 'app_user', 'localhost', 'sha256:...', 'BUILT_IN', '2024-01-15T08:10:00Z'),
    ('local-project', 'local-postgres', 'readonly_user', '10.0.0.0/8', 'sha256:...', 'BUILT_IN', '2024-03-01T10:10:00Z'),
    ('local-project', 'analytics-postgres', 'analytics_admin', '%', 'sha256:...', 'BUILT_IN', '2024-03-01T10:10:00Z'),
    ('local-project', 'analytics-postgres', 'metabase_user', '10.0.0.0/8', 'sha256:...', 'BUILT_IN', '2024-06-01T08:10:00Z'),
    ('local-project', 'local-mysql', 'wordpress', 'localhost', 'sha256:...', 'BUILT_IN', '2024-06-01T14:10:00Z'),
    ('local-project', 'wordpress-mysql', 'wp_admin', '%', 'sha256:...', 'BUILT_IN', '2024-09-01T09:15:00Z'),
    ('demo-project', 'demo-postgres', 'demo_user', '%', 'sha256:...', 'BUILT_IN', '2025-01-01T00:10:00Z');

INSERT INTO cloudsql_operations (operation_id, project_id, instance_id, operation_type, status, target_link, created_at) VALUES
    ('op-001', 'local-project', 'local-postgres', 'CREATE', 'DONE', 'projects/local-project/instances/local-postgres', '2024-01-15T08:00:00Z'),
    ('op-002', 'local-project', 'local-postgres', 'RESTART', 'DONE', 'projects/local-project/instances/local-postgres', '2024-03-15T10:00:00Z'),
    ('op-003', 'local-project', 'local-postgres', 'UPDATE', 'DONE', 'projects/local-project/instances/local-postgres', '2024-06-01T08:00:00Z'),
    ('op-004', 'local-project', 'analytics-postgres', 'CREATE', 'DONE', 'projects/local-project/instances/analytics-postgres', '2024-03-01T10:00:00Z'),
    ('op-005', 'local-project', 'local-mysql', 'CREATE', 'DONE', 'projects/local-project/instances/local-mysql', '2024-06-01T14:00:00Z'),
    ('op-006', 'local-project', 'local-postgres', 'CLONE', 'RUNNING', 'projects/local-project/instances/local-postgres/clone', '2025-05-20T08:00:00Z'),
    ('op-007', 'local-project', 'datawarehouse-postgres', 'START', 'PENDING', 'projects/local-project/instances/datawarehouse-postgres', '2025-05-20T08:30:00Z');

INSERT INTO cloudsql_flags (database_version, flag_name) VALUES
    ('POSTGRES_15', 'autoVacuum'), ('POSTGRES_15', 'maxConnections'),
    ('POSTGRES_15', 'sharedBuffers'), ('POSTGRES_15', 'effectiveCacheSize'),
    ('POSTGRES_15', 'workMem'), ('POSTGRES_15', 'maintenanceWorkMem'),
    ('POSTGRES_15', 'logStatement'), ('POSTGRES_15', 'logMinDurationStatement'),
    ('POSTGRES_15', 'timezone'), ('POSTGRES_16', 'maxConnections'),
    ('MYSQL_8_0', 'innodbBufferPoolSize'), ('MYSQL_8_0', 'maxConnections'),
    ('MYSQL_8_0', 'queryCacheType'), ('MYSQL_8_0', 'innodbLogFileSize');
