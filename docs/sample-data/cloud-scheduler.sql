-- Cloud Scheduler: Emulator Schema + Sample Data
-- Features: cron expressions, HTTP/PubSub/App Engine targets,
--           JSONB target config, BYTEA proto, execution history

CREATE TABLE IF NOT EXISTS scheduler_jobs (
    project_id          VARCHAR(255) NOT NULL,
    location_id         VARCHAR(255) NOT NULL,
    job_id              VARCHAR(255) NOT NULL,
    schedule            VARCHAR(255) NOT NULL,
    time_zone           VARCHAR(255) NOT NULL,
    target_type         VARCHAR(32) DEFAULT 'HTTP',
    target_config       JSONB DEFAULT '{}',
    state               VARCHAR(32) NOT NULL,
    attempt_deadline    BIGINT DEFAULT 1800,
    retry_count         INT DEFAULT 0,
    max_retry_count     INT DEFAULT 0,
    min_backoff         BIGINT DEFAULT 5,
    max_backoff         BIGINT DEFAULT 3600,
    max_doublings       INT DEFAULT 16,
    next_execution_time TIMESTAMP,
    job_proto           BYTEA NOT NULL,
    description         TEXT DEFAULT '',
    labels              JSONB DEFAULT '{}',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, job_id)
);

CREATE TABLE IF NOT EXISTS scheduler_executions (
    id          BIGSERIAL PRIMARY KEY,
    job_name    VARCHAR(1024) NOT NULL,
    status      VARCHAR(32) NOT NULL,
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    output      TEXT DEFAULT ''
);

INSERT INTO scheduler_jobs (project_id, location_id, job_id, schedule, time_zone, target_type, target_config, state, attempt_deadline, max_retry_count, description, labels, next_execution_time, created_at) VALUES
    -- HTTP target: daily report generation
    ('local-project', 'us-central1', 'daily-report-generation',
     '0 6 * * *', 'America/Los_Angeles', 'HTTP',
     '{"uri":"http://analytics-service/reports/daily","httpMethod":"POST","headers":{"Authorization":"Bearer ..."},"body":"{\"type\":\"daily\"}"}',
     'ENABLED', 600, 3, 'Generates and emails daily analytics reports',
     '{"team":"data","critical":"false"}', '2025-05-21T06:00:00Z', '2024-01-15T08:00:00Z'),

    -- HTTP target: hourly health check
    ('local-project', 'us-central1', 'health-check-ping',
     '0 * * * *', 'UTC', 'HTTP',
     '{"uri":"http://monitoring-service/health","httpMethod":"GET","headers":{}}',
     'ENABLED', 30, 2, 'Ping all services health endpoints hourly',
     '{"team":"sre","critical":"true"}', '2025-05-20T09:00:00Z', '2024-03-01T10:00:00Z'),

    -- Pub/Sub target: weekly cleanup
    ('local-project', 'us-central1', 'weekly-cleanup-job',
     '0 2 * * 0', 'America/New_York', 'PUB_SUB',
     '{"topicName":"projects/local-project/topics/maintenance-events","data":"{\"task\":\"cleanup\",\"ttl_days\":90}","attributes":{"source":"scheduler","job":"cleanup"}}',
     'ENABLED', 3600, 5, 'Weekly cleanup of stale data and temp files',
     '{"team":"platform","critical":"false"}', '2025-05-25T02:00:00Z', '2024-06-01T14:00:00Z'),

    -- HTTP target: monthly billing
    ('local-project', 'us-central1', 'monthly-billing-cycle',
     '0 0 1 * *', 'UTC', 'HTTP',
     '{"uri":"http://billing-service/run-billing","httpMethod":"POST","headers":{"Content-Type":"application/json"},"body":"{\"cycle\":\"monthly\"}"}',
     'ENABLED', 7200, 10, 'Monthly billing invoice generation and processing',
     '{"team":"finance","critical":"true"}', '2025-06-01T00:00:00Z', '2024-04-15T09:00:00Z'),

    -- HTTP target: session cleanup (every 15 min)
    ('local-project', 'us-central1', 'session-cleanup',
     '*/15 * * * *', 'UTC', 'HTTP',
     '{"uri":"http://auth-service/sessions/cleanup","httpMethod":"POST","headers":{},"body":"{\"max_age_hours\":24}"}',
     'ENABLED', 60, 2, 'Remove expired sessions every 15 minutes',
     '{"team":"backend","critical":"false"}', '2025-05-20T08:15:00Z', '2024-09-01T09:00:00Z'),

    -- Pub/Sub target: sync GCS bucket
    ('local-project', 'us-central1', 'gcs-bucket-sync',
     '0 */2 * * *', 'UTC', 'PUB_SUB',
     '{"topicName":"projects/local-project/topics/storage-sync","data":"{\"source\":\"user-profiles\",\"destination\":\"backup-archive\"}"}',
     'PAUSED', 1800, 3, 'Synchronize GCS buckets every 2 hours',
     '{"team":"data","critical":"false"}', NULL, '2024-10-01T08:00:00Z'),

    -- App Engine target: daily email digest
    ('local-project', 'us-central1', 'email-digest',
     '0 7 * * *', 'America/Los_Angeles', 'APP_ENGINE_HTTP',
     '{"uri":"/tasks/send-digest","httpMethod":"POST","headers":{},"body":"{\"type\":\"daily_digest\"}","appEngineRouting":{"service":"email-worker","version":"v1"}}',
     'ENABLED', 300, 3, 'Sends daily email digests to all users',
     '{"team":"backend","critical":"false"}', '2025-05-21T07:00:00Z', '2024-03-15T11:00:00Z'),

    ('local-project', 'us-central1', 'sync-cron-jobs',
     '0 0 * * 0', 'America/New_York', 'HTTP',
     '{"uri":"http://workflows-service/sync","httpMethod":"POST","headers":{},"body":"{\"source\":\"cron\"}"}',
     'ENABLED', 3600, 5, 'Weekly sync of scheduled jobs across regions',
     '{"team":"platform"}', '2025-05-25T00:00:00Z', '2025-01-01T00:00:00Z'),

    -- One-shot test job (disabled)
    ('local-project', 'us-central1', 'test-job',
     '0 0 1 1 *', 'UTC', 'HTTP',
     '{"uri":"http://localhost/debug","httpMethod":"GET"}',
     'DISABLED', 60, 0, 'Legacy test job',
     '{"team":"eng","temporary":"true"}', NULL, '2024-01-01T00:00:00Z'),

    ('demo-project', 'us-central1', 'demo-ping',
     '0 * * * *', 'UTC', 'HTTP',
     '{"uri":"http://demo-app/ping","httpMethod":"GET"}',
     'ENABLED', 30, 2, 'Demo health check',
     '{"env":"demo"}', '2025-05-20T09:00:00Z', '2025-01-01T00:00:00Z');

INSERT INTO scheduler_executions (job_name, status, executed_at, output) VALUES
    ('projects/local-project/locations/us-central1/jobs/health-check-ping', 'SUCCESS', '2025-05-20T08:00:00Z', '{"status":"healthy","latency_ms":45}'),
    ('projects/local-project/locations/us-central1/jobs/health-check-ping', 'SUCCESS', '2025-05-20T07:00:00Z', '{"status":"healthy","latency_ms":38}'),
    ('projects/local-project/locations/us-central1/jobs/health-check-ping', 'FAILED', '2025-05-20T06:00:00Z', '{"error":"Connection timeout","code":503}'),
    ('projects/local-project/locations/us-central1/jobs/health-check-ping', 'SUCCESS', '2025-05-20T05:00:00Z', '{"status":"healthy","latency_ms":42}'),
    ('projects/local-project/locations/us-central1/jobs/daily-report-generation', 'SUCCESS', '2025-05-20T06:00:00Z', '{"report_id":"rpt-123456","rows":15000,"duration_sec":234}'),
    ('projects/local-project/locations/us-central1/jobs/daily-report-generation', 'SUCCESS', '2025-05-19T06:00:00Z', '{"report_id":"rpt-123455","rows":14800,"duration_sec":218}'),
    ('projects/local-project/locations/us-central1/jobs/session-cleanup', 'SUCCESS', '2025-05-20T08:00:00Z', '{"removed":45,"total_checked":1200}'),
    ('projects/local-project/locations/us-central1/jobs/session-cleanup', 'SUCCESS', '2025-05-20T07:45:00Z', '{"removed":38,"total_checked":1180}');
