-- Cloud Tasks: Emulator Schema + Sample Data
-- Features: composite PK, FK with ON DELETE CASCADE, BYTEA,
--           task state machine, scheduling, dispatch tracking
--
-- Run against LocalCloud PostgreSQL. Backs the gRPC facade API.

CREATE TABLE IF NOT EXISTS task_queues (
    project_id                VARCHAR(255) NOT NULL,
    location_id               VARCHAR(255) NOT NULL,
    queue_id                  VARCHAR(255) NOT NULL,
    state                     VARCHAR(20) DEFAULT 'RUNNING',
    max_dispatches_per_second DOUBLE PRECISION DEFAULT 500,
    max_concurrent_dispatches INT DEFAULT 1000,
    max_attempts              INT DEFAULT 100,
    max_retry_duration        INT DEFAULT 86400,
    min_backoff               INT DEFAULT 10,
    max_backoff               INT DEFAULT 3600,
    max_doublings             INT DEFAULT 16,
    rate_limit_exceeded       BOOLEAN DEFAULT FALSE,
    created_at                TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, queue_id)
);

CREATE TABLE IF NOT EXISTS cloud_tasks (
    task_id         VARCHAR(500) PRIMARY KEY,
    queue_name      VARCHAR(255) NOT NULL,
    project_id      VARCHAR(255) NOT NULL,
    location_id     VARCHAR(255) NOT NULL,
    http_method     VARCHAR(10),
    url             VARCHAR(2000),
    headers         JSONB DEFAULT '{}',
    body            BYTEA,
    schedule_time   TIMESTAMP,
    dispatch_count  INT DEFAULT 0,
    response_count  INT DEFAULT 0,
    last_dispatch   TIMESTAMP,
    last_response   TIMESTAMP,
    last_status     INT,
    state           VARCHAR(20) DEFAULT 'PENDING',
    max_attempts    INT DEFAULT 100,
    attempt_deadline INT DEFAULT 600,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_queue FOREIGN KEY (project_id, location_id, queue_name)
        REFERENCES task_queues(project_id, location_id, queue_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_cloud_tasks_queue
    ON cloud_tasks (project_id, location_id, queue_name, state);

CREATE INDEX IF NOT EXISTS idx_cloud_tasks_schedule
    ON cloud_tasks (schedule_time) WHERE state = 'PENDING';

INSERT INTO task_queues (project_id, location_id, queue_id, state, max_dispatches_per_second, max_concurrent_dispatches, max_attempts, max_retry_duration, min_backoff, max_backoff) VALUES
    ('local-project', 'us-central1', 'email-queue', 'RUNNING', 100, 50, 5, 86400, 10, 3600),
    ('local-project', 'us-central1', 'payment-queue', 'RUNNING', 500, 100, 10, 604800, 30, 86400),
    ('local-project', 'us-central1', 'notification-queue', 'RUNNING', 50, 25, 3, 3600, 5, 300),
    ('local-project', 'us-central1', 'webhook-queue', 'PAUSED', 200, 50, 8, 259200, 60, 7200),
    ('local-project', 'us-central1', 'data-processing-queue', 'RUNNING', 50, 10, 3, 86400, 60, 3600),
    ('local-project', 'us-central1', 'image-processing-queue', 'RUNNING', 20, 5, 3, 3600, 10, 600),
    ('local-project', 'europe-west1', 'analytics-queue', 'RUNNING', 100, 20, 5, 43200, 30, 1800),
    ('local-project', 'asia-east1', 'sync-queue', 'RUNNING', 50, 10, 5, 86400, 60, 3600)
ON CONFLICT (project_id, location_id, queue_id) DO NOTHING;

INSERT INTO cloud_tasks (task_id, queue_name, project_id, location_id, http_method, url, headers, schedule_time, dispatch_count, state, max_attempts, created_at) VALUES
    ('task-email-001', 'email-queue', 'local-project', 'us-central1', 'POST', 'http://email-service/send',
     '{"Content-Type":"application/json"}', NULL, 0, 'PENDING', 5, '2025-05-20T08:00:00Z'),
    ('task-email-002', 'email-queue', 'local-project', 'us-central1', 'POST', 'http://email-service/send/batch',
     '{"Content-Type":"application/json"}', '2025-05-20T10:00:00Z', 0, 'PENDING', 5, '2025-05-20T08:00:00Z'),
    ('task-payment-001', 'payment-queue', 'local-project', 'us-central1', 'POST', 'http://payment-service/charge',
     '{"Content-Type":"application/json","Idempotency-Key":"abc-123"}', NULL, 2, 'ATTEMPTED', 10, '2025-05-20T07:55:00Z'),
    ('task-payment-002', 'payment-queue', 'local-project', 'us-central1', 'POST', 'http://payment-service/refund',
     '{"Content-Type":"application/json"}', NULL, 1, 'ATTEMPTED', 10, '2025-05-20T08:00:00Z'),
    ('task-payment-003', 'payment-queue', 'local-project', 'us-central1', 'POST', 'http://payment-service/charge',
     '{"Content-Type":"application/json","Idempotency-Key":"def-456"}', NULL, 0, 'PENDING', 10, '2025-05-20T08:05:00Z'),
    ('task-notify-001', 'notification-queue', 'local-project', 'us-central1', 'POST', 'http://notify-service/push',
     '{"Content-Type":"application/json"}', NULL, 3, 'COMPLETED', 3, '2025-05-20T07:50:00Z'),
    ('task-notify-002', 'notification-queue', 'local-project', 'us-central1', 'POST', 'http://notify-service/sms',
     '{"Content-Type":"application/json"}', NULL, 0, 'PENDING', 3, '2025-05-20T08:10:00Z'),
    ('task-webhook-001', 'webhook-queue', 'local-project', 'us-central1', 'POST', 'https://hooks.example.com/events',
     '{"Content-Type":"application/json","X-Signature":"sha256=..."}', NULL, 5, 'FAILED', 8, '2025-05-20T07:00:00Z'),
    ('task-data-001', 'data-processing-queue', 'local-project', 'us-central1', 'POST', 'http://data-service/transform',
     '{"Content-Type":"application/json"}', NULL, 1, 'ATTEMPTED', 3, '2025-05-20T08:00:00Z'),
    ('task-data-002', 'data-processing-queue', 'local-project', 'us-central1', 'POST', 'http://data-service/aggregate',
     '{"Content-Type":"application/json"}', '2025-05-21T00:00:00Z', 0, 'PENDING', 3, '2025-05-20T08:00:00Z'),
    ('task-img-001', 'image-processing-queue', 'local-project', 'us-central1', 'POST', 'http://img-service/resize',
     '{"Content-Type":"application/json"}', NULL, 0, 'PENDING', 3, '2025-05-20T08:02:00Z'),
    ('task-img-002', 'image-processing-queue', 'local-project', 'us-central1', 'POST', 'http://img-service/optimize',
     '{"Content-Type":"application/json"}', NULL, 0, 'PENDING', 3, '2025-05-20T08:03:00Z');

-- Query: task performance by queue
-- SELECT q.queue_id, COUNT(t.task_id) as total_tasks,
--        SUM(CASE WHEN t.state = 'COMPLETED' THEN 1 ELSE 0 END) as completed,
--        SUM(CASE WHEN t.state = 'FAILED' THEN 1 ELSE 0 END) as failed
-- FROM task_queues q
-- LEFT JOIN cloud_tasks t ON q.project_id = t.project_id AND q.queue_id = t.queue_name
-- GROUP BY q.queue_id;
