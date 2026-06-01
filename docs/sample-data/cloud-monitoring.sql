-- Cloud Monitoring: Emulator Schema + Sample Data
-- Features: time series + metric points with multiple value types,
--           alert policies, notification channels, dashboards,
--           MQL-style queries
--
-- Run against LocalCloud PostgreSQL.

CREATE TABLE IF NOT EXISTS time_series (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL DEFAULT 'local-project',
    project_name    VARCHAR(1024) NOT NULL,
    metric_type     VARCHAR(1024) NOT NULL,
    metric_labels   JSONB DEFAULT '{}',
    resource_type   VARCHAR(255) DEFAULT '',
    resource_labels JSONB DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS metric_points (
    id            VARCHAR(255) NOT NULL PRIMARY KEY,
    project_id    VARCHAR(255) NOT NULL DEFAULT 'local-project',
    series_id     VARCHAR(255) NOT NULL,
    start_time    BIGINT DEFAULT 0,
    end_time      BIGINT DEFAULT 0,
    value_type    VARCHAR(20) DEFAULT 'DOUBLE',
    double_value  DOUBLE PRECISION DEFAULT 0,
    int_value     BIGINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_metric_points_series_id ON metric_points (series_id);
CREATE INDEX IF NOT EXISTS idx_metric_points_timestamp ON metric_points (end_time DESC);

-- CPU utilization time series
INSERT INTO time_series (id, project_id, project_name, metric_type, metric_labels, resource_type, resource_labels)
VALUES
    ('ts-cpu-001', 'local-project', 'projects/local-project', 'compute.googleapis.com/instance/cpu/utilization',
     '{"instance_name":"instance-001","zone":"us-central1-a"}', 'gce_instance',
     '{"project_id":"local-project","instance_id":"123456789"}'),
    ('ts-cpu-002', 'local-project', 'projects/local-project', 'compute.googleapis.com/instance/cpu/utilization',
     '{"instance_name":"instance-002","zone":"us-central1-a"}', 'gce_instance',
     '{"project_id":"local-project","instance_id":"123456790"}'),
    ('ts-cpu-003', 'local-project', 'projects/local-project', 'compute.googleapis.com/instance/cpu/utilization',
     '{"instance_name":"instance-003","zone":"us-west1-a"}', 'gce_instance',
     '{"project_id":"local-project","instance_id":"123456791"}'),

    -- HTTP request count (api_gateway)
    ('ts-http-001', 'local-project', 'projects/local-project', 'apigateway.googleapis.com/request_count',
     '{"gateway":"api-gw-001","response_code":"2xx"}', 'api_gateway',
     '{"project_id":"local-project","location":"us-central1"}'),
    ('ts-http-002', 'local-project', 'projects/local-project', 'apigateway.googleapis.com/request_count',
     '{"gateway":"api-gw-001","response_code":"5xx"}', 'api_gateway',
     '{"project_id":"local-project","location":"us-central1"}'),

    -- Pub/Sub metrics
    ('ts-pubsub-001', 'local-project', 'projects/local-project', 'pubsub.googleapis.com/subscription/num_undelivered_messages',
     '{"subscription_id":"user-events-processor","topic_id":"user-events"}', 'pubsub_subscription',
     '{"project_id":"local-project"}'),
    ('ts-pubsub-002', 'local-project', 'projects/local-project', 'pubsub.googleapis.com/subscription/ack_message_count',
     '{"subscription_id":"user-events-processor","topic_id":"user-events"}', 'pubsub_subscription',
     '{"project_id":"local-project"}'),

    -- Storage metrics
    ('ts-storage-001', 'local-project', 'projects/local-project', 'storage.googleapis.com/storage/total_bytes',
     '{"bucket_name":"user-projects"}', 'gcs_bucket',
     '{"project_id":"local-project","location":"US"}'),

    -- Custom application metrics
    ('ts-custom-001', 'local-project', 'projects/local-project', 'custom.googleapis.com/app/response_latency',
     '{"service":"api-gateway","method":"GET"}', 'global',
     '{"project_id":"local-project"}'),
    ('ts-custom-002', 'local-project', 'projects/local-project', 'custom.googleapis.com/app/response_latency',
     '{"service":"user-service","method":"GET"}', 'global',
     '{"project_id":"local-project"}'),
    ('ts-custom-003', 'local-project', 'projects/local-project', 'custom.googleapis.com/app/error_rate',
     '{"service":"api-gateway"}', 'global',
     '{"project_id":"local-project"}'),

    -- BigQuery metrics
    ('ts-bq-001', 'local-project', 'projects/local-project', 'bigquery.googleapis.com/query/execution_times',
     '{"project_id":"local-project"}', 'bigquery_project',
     '{"project_id":"local-project"}'),
    ('ts-bq-002', 'local-project', 'projects/local-project', 'bigquery.googleapis.com/query/processed_bytes',
     '{"project_id":"local-project"}', 'bigquery_project',
     '{"project_id":"local-project"}');

-- Metric points: 5 data points per time series (simulating 5-min window)
INSERT INTO metric_points (id, project_id, series_id, start_time, end_time, value_type, double_value, int_value) VALUES
    -- CPU utilization: instance-001 (5 min window)
    ('mp-cpu-001-1', 'local-project', 'ts-cpu-001', 1747708800000, 1747709100000, 'DOUBLE', 0.45, 0),
    ('mp-cpu-001-2', 'local-project', 'ts-cpu-001', 1747709100000, 1747709400000, 'DOUBLE', 0.62, 0),
    ('mp-cpu-001-3', 'local-project', 'ts-cpu-001', 1747709400000, 1747709700000, 'DOUBLE', 0.78, 0),
    ('mp-cpu-001-4', 'local-project', 'ts-cpu-001', 1747709700000, 1747710000000, 'DOUBLE', 0.55, 0),
    ('mp-cpu-001-5', 'local-project', 'ts-cpu-001', 1747710000000, 1747710300000, 'DOUBLE', 0.38, 0),
    -- CPU utilization: instance-002 (steady)
    ('mp-cpu-002-1', 'local-project', 'ts-cpu-002', 1747708800000, 1747709100000, 'DOUBLE', 0.22, 0),
    ('mp-cpu-002-2', 'local-project', 'ts-cpu-002', 1747709100000, 1747709400000, 'DOUBLE', 0.24, 0),
    ('mp-cpu-002-3', 'local-project', 'ts-cpu-002', 1747709400000, 1747709700000, 'DOUBLE', 0.21, 0),
    ('mp-cpu-002-4', 'local-project', 'ts-cpu-002', 1747709700000, 1747710000000, 'DOUBLE', 0.23, 0),
    ('mp-cpu-002-5', 'local-project', 'ts-cpu-002', 1747710000000, 1747710300000, 'DOUBLE', 0.20, 0),
    -- CPU utilization: instance-003 (low)
    ('mp-cpu-003-1', 'local-project', 'ts-cpu-003', 1747708800000, 1747709100000, 'DOUBLE', 0.08, 0),
    ('mp-cpu-003-2', 'local-project', 'ts-cpu-003', 1747709100000, 1747709400000, 'DOUBLE', 0.09, 0),
    ('mp-cpu-003-3', 'local-project', 'ts-cpu-003', 1747709400000, 1747709700000, 'DOUBLE', 0.07, 0),
    ('mp-cpu-003-4', 'local-project', 'ts-cpu-003', 1747709700000, 1747710000000, 'DOUBLE', 0.08, 0),
    ('mp-cpu-003-5', 'local-project', 'ts-cpu-003', 1747710000000, 1747710300000, 'DOUBLE', 0.06, 0),
    -- HTTP request count: 2xx (int)
    ('mp-http-001-1', 'local-project', 'ts-http-001', 1747708800000, 1747709100000, 'INT64', 0, 1200),
    ('mp-http-001-2', 'local-project', 'ts-http-001', 1747709100000, 1747709400000, 'INT64', 0, 1350),
    ('mp-http-001-3', 'local-project', 'ts-http-001', 1747709400000, 1747709700000, 'INT64', 0, 1450),
    ('mp-http-001-4', 'local-project', 'ts-http-001', 1747709700000, 1747710000000, 'INT64', 0, 1100),
    ('mp-http-001-5', 'local-project', 'ts-http-001', 1747710000000, 1747710300000, 'INT64', 0, 980),
    -- HTTP request count: 5xx (errors)
    ('mp-http-002-1', 'local-project', 'ts-http-002', 1747708800000, 1747709100000, 'INT64', 0, 2),
    ('mp-http-002-2', 'local-project', 'ts-http-002', 1747709100000, 1747709400000, 'INT64', 0, 5),
    ('mp-http-002-3', 'local-project', 'ts-http-002', 1747709400000, 1747709700000, 'INT64', 0, 15),
    ('mp-http-002-4', 'local-project', 'ts-http-002', 1747709700000, 1747710000000, 'INT64', 0, 3),
    ('mp-http-002-5', 'local-project', 'ts-http-002', 1747710000000, 1747710300000, 'INT64', 0, 1),
    -- Pub/Sub: undelivered messages
    ('mp-ps-001-1', 'local-project', 'ts-pubsub-001', 1747708800000, 1747709100000, 'INT64', 0, 456),
    ('mp-ps-001-2', 'local-project', 'ts-pubsub-001', 1747709100000, 1747709400000, 'INT64', 0, 423),
    ('mp-ps-001-3', 'local-project', 'ts-pubsub-001', 1747709400000, 1747709700000, 'INT64', 0, 389),
    ('mp-ps-001-4', 'local-project', 'ts-pubsub-001', 1747709700000, 1747710000000, 'INT64', 0, 412),
    ('mp-ps-001-5', 'local-project', 'ts-pubsub-001', 1747710000000, 1747710300000, 'INT64', 0, 378),
    -- Pub/Sub: acked messages
    ('mp-ps-002-1', 'local-project', 'ts-pubsub-002', 1747708800000, 1747709100000, 'INT64', 0, 89),
    ('mp-ps-002-2', 'local-project', 'ts-pubsub-002', 1747709100000, 1747709400000, 'INT64', 0, 95),
    ('mp-ps-002-3', 'local-project', 'ts-pubsub-002', 1747709400000, 1747709700000, 'INT64', 0, 78),
    ('mp-ps-002-4', 'local-project', 'ts-pubsub-002', 1747709700000, 1747710000000, 'INT64', 0, 102),
    ('mp-ps-002-5', 'local-project', 'ts-pubsub-002', 1747710000000, 1747710300000, 'INT64', 0, 88),
    -- Storage bytes
    ('mp-str-001-1', 'local-project', 'ts-storage-001', 1747708800000, 1747709100000, 'INT64', 0, 1048576000),
    ('mp-str-001-2', 'local-project', 'ts-storage-001', 1747709100000, 1747709400000, 'INT64', 0, 1048577000),
    ('mp-str-001-3', 'local-project', 'ts-storage-001', 1747709400000, 1747709700000, 'INT64', 0, 1048578500),
    ('mp-str-001-4', 'local-project', 'ts-storage-001', 1747709700000, 1747710000000, 'INT64', 0, 1048579000),
    ('mp-str-001-5', 'local-project', 'ts-storage-001', 1747710000000, 1747710300000, 'INT64', 0, 1048580000),
    -- Custom latency p50
    ('mp-cust-001-1', 'local-project', 'ts-custom-001', 1747708800000, 1747709100000, 'DOUBLE', 45.2, 0),
    ('mp-cust-001-2', 'local-project', 'ts-custom-001', 1747709100000, 1747709400000, 'DOUBLE', 52.8, 0),
    ('mp-cust-001-3', 'local-project', 'ts-custom-001', 1747709400000, 1747709700000, 'DOUBLE', 68.1, 0),
    ('mp-cust-001-4', 'local-project', 'ts-custom-001', 1747709700000, 1747710000000, 'DOUBLE', 55.3, 0),
    ('mp-cust-001-5', 'local-project', 'ts-custom-001', 1747710000000, 1747710300000, 'DOUBLE', 42.5, 0),
    -- Error rate
    ('mp-cust-003-1', 'local-project', 'ts-custom-003', 1747708800000, 1747709100000, 'DOUBLE', 0.001, 0),
    ('mp-cust-003-2', 'local-project', 'ts-custom-003', 1747709100000, 1747709400000, 'DOUBLE', 0.003, 0),
    ('mp-cust-003-3', 'local-project', 'ts-custom-003', 1747709400000, 1747709700000, 'DOUBLE', 0.008, 0),
    ('mp-cust-003-4', 'local-project', 'ts-custom-003', 1747709700000, 1747710000000, 'DOUBLE', 0.004, 0),
    ('mp-cust-003-5', 'local-project', 'ts-custom-003', 1747710000000, 1747710300000, 'DOUBLE', 0.002, 0);

-- Query: average CPU utilization by instance (last 30 min)
-- SELECT mp.series_id, ts.metric_labels->>'instance_name' as instance,
--        AVG(mp.double_value) as avg_cpu
-- FROM metric_points mp
-- JOIN time_series ts ON mp.series_id = ts.id
-- WHERE ts.metric_type = 'compute.googleapis.com/instance/cpu/utilization'
--   AND mp.end_time > 1747708800000
-- GROUP BY mp.series_id, instance;

-- Query: 5xx error rate trend
-- SELECT mp.end_time as timestamp, mp.int_value as error_count,
--        CAST(mp.int_value AS FLOAT) / NULLIF(
--          (SELECT AVG(mp2.int_value) FROM metric_points mp2 WHERE mp2.series_id = 'ts-http-001'
--           AND mp2.end_time BETWEEN mp.end_time - 300000 AND mp.end_time), 0) * 100 as error_pct
-- FROM metric_points mp
-- WHERE mp.series_id = 'ts-http-002'
-- ORDER BY mp.end_time;
