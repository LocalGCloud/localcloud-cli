-- Cloud Run: Emulator Schema + Sample Data
-- Features: services with revisions, traffic splitting, env vars,
--           container lifecycle, URI generation

CREATE TABLE IF NOT EXISTS cloudrun_services (
    project_id      VARCHAR(255) NOT NULL,
    location        VARCHAR(255) NOT NULL,
    service_id      VARCHAR(255) NOT NULL,
    container_image VARCHAR(512) NOT NULL,
    container_port  INT DEFAULT 8080,
    container_id    VARCHAR(255),
    host_port       INT,
    uri             VARCHAR(1024),
    env_vars        JSONB DEFAULT '{}',
    revision_count  INT DEFAULT 1,
    max_instances   INT DEFAULT 10,
    min_instances   INT DEFAULT 0,
    concurrency     INT DEFAULT 80,
    cpu             VARCHAR(20) DEFAULT '1',
    memory          VARCHAR(20) DEFAULT '512Mi',
    ingress         VARCHAR(20) DEFAULT 'all',
    service_account VARCHAR(512),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location, service_id)
);

CREATE TABLE IF NOT EXISTS cloudrun_revisions (
    project_id      VARCHAR(255) NOT NULL,
    location        VARCHAR(255) NOT NULL,
    service_id      VARCHAR(255) NOT NULL,
    revision_id     VARCHAR(255) NOT NULL,
    container_image VARCHAR(512) NOT NULL,
    container_id    VARCHAR(255),
    traffic_percent INT DEFAULT 0,
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location, service_id, revision_id)
);

INSERT INTO cloudrun_services (project_id, location, service_id, container_image, container_port, container_id, host_port, uri, env_vars, revision_count, max_instances, min_instances, concurrency, cpu, memory, ingress, service_account, created_at, updated_at) VALUES
    ('local-project', 'us-central1', 'user-service', 'us.gcr.io/local-project/user-service:v2.1.0', 8080,
     'abc123def456', 58080, 'https://user-service-abc123-uc.a.run.app',
     '{"DATABASE_URL":"postgresql://user:pass@db:5432/users","REDIS_URL":"redis://redis:6379","LOG_LEVEL":"info","ENVIRONMENT":"production"}',
     5, 20, 2, 80, '1', '512Mi', 'all', 'run-sa@local-project.iam.gserviceaccount.com',
     '2024-01-15T08:00:00Z', '2025-05-20T10:00:00Z'),

    ('local-project', 'us-central1', 'payment-service', 'us.gcr.io/local-project/payment-service:v1.5.2', 8080,
     'def789ghi012', 58081, 'https://payment-service-def789-uc.a.run.app',
     '{"STRIPE_KEY":"sk_live_...","DATABASE_URL":"postgresql://user:pass@db:5432/payments","LOG_LEVEL":"warn","TIMEOUT_SEC":"30"}',
     12, 50, 3, 50, '2', '1Gi', 'internal', 'payments-sa@local-project.iam.gserviceaccount.com',
     '2024-01-20T09:00:00Z', '2025-05-19T14:00:00Z'),

    ('local-project', 'us-central1', 'notification-service', 'us.gcr.io/local-project/notification-service:v3.0.1', 8080,
     'ghi345jkl678', 58082, 'https://notification-service-ghi345-uc.a.run.app',
     '{"SLACK_WEBHOOK":"https://hooks.slack.com/...","SENDGRID_KEY":"SG.xxxx","LOG_LEVEL":"info"}',
     3, 10, 0, 80, '1', '256Mi', 'all', 'notify-sa@local-project.iam.gserviceaccount.com',
     '2024-03-01T10:00:00Z', '2025-05-18T09:00:00Z'),

    ('local-project', 'europe-west1', 'analytics-worker', 'us.gcr.io/local-project/analytics-worker:v0.9.0', 9090,
     'jkl901mno234', 59090, 'https://analytics-worker-jkl901-ew.a.run.app',
     '{"BIGQUERY_DATASET":"app_analytics","BATCH_SIZE":"1000","LOG_LEVEL":"debug"}',
     8, 30, 1, 10, '4', '2Gi', 'internal', 'analytics-sa@local-project.iam.gserviceaccount.com',
     '2024-04-10T14:00:00Z', '2025-05-17T12:00:00Z'),

    ('local-project', 'us-central1', 'frontend-service', 'us.gcr.io/local-project/frontend:v4.2.0', 3000,
     'pqr567stu890', 53000, 'https://frontend-service-pqr567-uc.a.run.app',
     '{"API_URL":"https://api.localcloud.dev","NODE_ENV":"production","SENTRY_DSN":"https://xxx@o000.ingest.sentry.io/111"}',
     15, 30, 3, 100, '2', '1Gi', 'all', 'frontend-sa@local-project.iam.gserviceaccount.com',
     '2024-01-10T08:00:00Z', '2025-05-20T12:00:00Z');

INSERT INTO cloudrun_revisions (project_id, location, service_id, revision_id, container_image, traffic_percent, status, created_at) VALUES
    -- user-service: revisions showing rolling update
    ('local-project', 'us-central1', 'user-service', 'user-service-00001', 'us.gcr.io/local-project/user-service:v1.0.0', 0, 'INACTIVE', '2024-01-15T08:00:00Z'),
    ('local-project', 'us-central1', 'user-service', 'user-service-00002', 'us.gcr.io/local-project/user-service:v1.1.0', 0, 'INACTIVE', '2024-03-20T10:00:00Z'),
    ('local-project', 'us-central1', 'user-service', 'user-service-00003', 'us.gcr.io/local-project/user-service:v2.0.0', 0, 'INACTIVE', '2024-08-10T14:00:00Z'),
    ('local-project', 'us-central1', 'user-service', 'user-service-00004', 'us.gcr.io/local-project/user-service:v2.1.0-rc1', 5, 'ACTIVE', '2025-05-15T09:00:00Z'),
    ('local-project', 'us-central1', 'user-service', 'user-service-00005', 'us.gcr.io/local-project/user-service:v2.1.0', 95, 'ACTIVE', '2025-05-20T10:00:00Z'),

    -- payment-service: 12 revisions, showing canary deployment
    ('local-project', 'us-central1', 'payment-service', 'payment-service-00011', 'us.gcr.io/local-project/payment-service:v1.5.1', 10, 'ACTIVE', '2025-05-18T08:00:00Z'),
    ('local-project', 'us-central1', 'payment-service', 'payment-service-00012', 'us.gcr.io/local-project/payment-service:v1.5.2', 90, 'ACTIVE', '2025-05-19T14:00:00Z'),

    -- frontend-service: 15 revisions, with active staging rev
    ('local-project', 'us-central1', 'frontend-service', 'frontend-service-00014', 'us.gcr.io/local-project/frontend:v4.1.0', 20, 'ACTIVE', '2025-05-10T11:00:00Z'),
    ('local-project', 'us-central1', 'frontend-service', 'frontend-service-00015', 'us.gcr.io/local-project/frontend:v4.2.0', 80, 'ACTIVE', '2025-05-20T12:00:00Z');

-- Query: revision traffic distribution for a service
-- SELECT revision_id, traffic_percent, status
-- FROM cloudrun_revisions
-- WHERE project_id = 'local-project' AND service_id = 'user-service'
-- ORDER BY created_at DESC;

-- Query: services with their latest revision and total instances
-- SELECT s.service_id, s.container_image, s.min_instances || '-' || s.max_instances as scaling,
--        MAX(r.created_at) as latest_revision
-- FROM cloudrun_services s
-- LEFT JOIN cloudrun_revisions r ON s.project_id = r.project_id AND s.service_id = r.service_id
-- GROUP BY s.service_id, s.container_image, s.min_instances, s.max_instances;
