-- Cloud Logging: Emulator Schema + Sample Data
-- Features: multiple resource types, severity levels, JSON payloads,
--           log-based metrics, log sinks, indexes on high-volume columns
--
-- Run against LocalCloud PostgreSQL.

CREATE TABLE IF NOT EXISTS log_entries (
    id              VARCHAR(255) NOT NULL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL DEFAULT '',
    log_name        VARCHAR(1024) NOT NULL,
    resource_type   VARCHAR(255) DEFAULT '',
    resource_labels JSONB DEFAULT '{}',
    severity        VARCHAR(20) DEFAULT 'DEFAULT',
    text_payload    TEXT DEFAULT '',
    json_payload    JSONB DEFAULT '{}',
    labels          JSONB DEFAULT '{}',
    timestamp       BIGINT DEFAULT 0,
    insert_id       VARCHAR(255) DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_log_entries_log_name ON log_entries (log_name);
CREATE INDEX IF NOT EXISTS idx_log_entries_timestamp ON log_entries (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_log_entries_severity ON log_entries (severity);
CREATE INDEX IF NOT EXISTS idx_log_entries_project ON log_entries (project_id, timestamp DESC);

INSERT INTO log_entries (id, project_id, log_name, resource_type, resource_labels, severity, text_payload, json_payload, labels, timestamp, insert_id) VALUES
-- API Gateway access logs
('log-001', 'local-project', 'projects/local-project/logs/apigateway', 'api_gateway',
 '{"gateway":"api-gw-001","region":"us-central1"}', 'INFO',
 'GET /v1/projects/local-project/datasets - 200 OK',
 '{}', '{"method":"GET","endpoint":"/v1/projects/{project}/datasets","status":200,"latency_ms":145}', 1747708800000, 'ins-001'),

('log-002', 'local-project', 'projects/local-project/logs/apigateway', 'api_gateway',
 '{"gateway":"api-gw-001","region":"us-central1"}', 'ERROR',
 'POST /v1/projects/local-project/datasets - 503 Service Unavailable',
 '{}', '{"method":"POST","endpoint":"/v1/projects/{project}/datasets","status":503,"latency_ms":30250}', 1747708860000, 'ins-002'),

('log-003', 'local-project', 'projects/local-project/logs/apigateway', 'api_gateway',
 '{"gateway":"api-gw-001","region":"us-central1"}', 'WARNING',
 'Rate limit exceeded for client 192.168.1.100 - 429 Too Many Requests',
 '{"client_ip":"192.168.1.100","rate_limit":"100/min","current":102}', '{"method":"GET","endpoint":"/v1/projects/{project}/buckets","status":429,"latency_ms":2}', 1747708920000, 'ins-003'),

-- App Engine logs
('log-004', 'local-project', 'projects/local-project/logs/appengine', 'gae_app',
 '{"module_id":"default","version_id":"v2-1-0","zone":"us-central1-a"}', 'INFO',
 'Request completed successfully',
 '{"request_id":"req-abc-001","method":"GET","path":"/dashboard","latency":245,"user_agent":"Mozilla/5.0"}', '{}', 1747708980000, 'ins-004'),

('log-005', 'local-project', 'projects/local-project/logs/appengine', 'gae_app',
 '{"module_id":"worker","version_id":"v2-1-0","zone":"us-central1-a"}', 'ERROR',
 'Unhandled exception processing task task-payment-001: TimeoutError: Payment gateway timeout after 30s',
 '{"task_id":"task-payment-001","error_type":"TimeoutError","message":"Payment gateway timeout after 30s","stack_trace":"..."}', '{"severity":"ERROR","component":"payment-worker"}', 1747709040000, 'ins-005'),

-- Cloud Run logs
('log-006', 'local-project', 'projects/local-project/logs/run.googleapis.com', 'cloud_run_revision',
 '{"service_name":"user-service","revision_name":"user-service-00005","configuration_name":"user-service","location":"us-central1"}', 'INFO',
 'Started processing request GET /api/v1/users',
 '{}', '{"method":"GET","path":"/api/v1/users","request_id":"req-002"}', 1747709100000, 'ins-006'),

('log-007', 'local-project', 'projects/local-project/logs/run.googleapis.com', 'cloud_run_revision',
 '{"service_name":"payment-service","revision_name":"payment-service-00012","configuration_name":"payment-service","location":"us-west1"}', 'CRITICAL',
 'OOM killer terminated process: memory usage exceeded 512MB limit',
 '{"container":"payment-service","memory_usage_mb":580,"limit_mb":512,"oom_score":850,"pid":1234}', '{"severity":"CRITICAL","team":"sre","oncall":true}', 1747709160000, 'ins-007'),

-- GKE logs
('log-008', 'local-project', 'projects/local-project/logs/container.googleapis.com', 'k8s_container',
 '{"cluster_name":"prod-cluster","namespace_name":"default","pod_name":"api-gateway-7d8f9c6b5-x4h3k","container_name":"gateway"}', 'INFO',
 'HTTP server started on port 8080',
 '{}', '{"component":"server","status":"started","port":8080}', 1747709220000, 'ins-008'),

('log-009', 'local-project', 'projects/local-project/logs/container.googleapis.com', 'k8s_pod',
 '{"cluster_name":"prod-cluster","namespace_name":"default","pod_name":"ml-service-5c4d9e8f7-y2b1n"}', 'WARNING',
 'Pod is using 90% of requested CPU resources',
 '{"cpu_usage_mcores":450,"cpu_request_mcores":500,"cpu_limit_mcores":1000,"memory_usage_mb":2048}', '{"severity":"WARNING","metric":"resource_usage"}', 1747709280000, 'ins-009'),

-- Audit logs
('log-010', 'local-project', 'projects/local-project/logs/cloudaudit.googleapis.com', 'audited_resource',
 '{"service":"iam.googleapis.com","method":"google.iam.admin.v1.SetIamPolicy","resource":"projects/local-project"}', 'INFO',
 'IAM policy updated by admin@localcloud: added role roles/storage.objectAdmin for user bob@techcorp.com',
 '{"principal":"admin@localcloud","action":"update","resource":"projects/local-project","diff":[{"add":{"role":"roles/storage.objectAdmin","members":["user:bob@techcorp.com"]}}]}', '{"method":"SetIamPolicy","service":"iam"}', 1747709340000, 'ins-010'),

-- Database logs
('log-011', 'local-project', 'projects/local-project/logs/cloudsql.googleapis.com', 'cloudsql_database',
 '{"database":"app_db","instance_id":"local-postgres"}', 'INFO',
 'Connection acquired from pool: conn-42 (pool utilization: 45%)',
 '{"conn_id":"conn-42","pool_utilization":0.45,"wait_time_ms":2,"database":"app_db"}', '{}', 1747709400000, 'ins-011'),

('log-012', 'local-project', 'projects/local-project/logs/cloudsql.googleapis.com', 'cloudsql_database',
 '{"database":"app_db","instance_id":"local-postgres"}', 'ERROR',
 'Query timeout: SELECT * FROM large_table WHERE condition - exceeded 30s limit',
 '{"query":"SELECT * FROM large_table WHERE condition","duration_ms":35200,"rows_examined":5000000,"error":"canceling statement due to statement timeout"}', '{"severity":"ERROR","team":"dba"}', 1747709460000, 'ins-012'),

-- Security logs
('log-013', 'local-project', 'projects/local-project/logs/security.googleapis.com', 'security_center',
 '{"finding_id":"finding-001","category":"OPEN_FIREWALL"}', 'HIGH',
 'Firewall rule default-allow-ssh allows unrestricted ingress on port 22 from 0.0.0.0/0',
 '{"resource":"projects/local-project/firewalls/default-allow-ssh","port":"22","protocol":"tcp","source_ranges":["0.0.0.0/0"],"recommended_action":"Restrict to authorized IPs"}', '{"priority":"HIGH","category":"networking"}', 1747709520000, 'ins-013'),

('log-014', 'local-project', 'projects/local-project/logs/security.googleapis.com', 'security_center',
 '{"finding_id":"finding-002","category":"PUBLIC_BUCKET"}', 'MEDIUM',
 'Bucket "user-profiles" has public read access enabled',
 '{"bucket":"user-profiles","project":"local-project","acl":["allUsers:READER"],"recommended_action":"Remove public access"}', '{"priority":"MEDIUM","category":"storage"}', 1747709580000, 'ins-014'),

-- Custom application logs
('log-015', 'local-project', 'projects/local-project/logs/myapp', 'global', '{}', 'DEFAULT',
 'Application started successfully',
 '{"app":"myapp","version":"1.2.3","env":"production","hostname":"app-01","pid":5678,"startup_time_ms":3450}', '{"env":"production","app":"myapp"}', 1747709640000, 'ins-015');

-- Query: error rate by service (last hour)
-- SELECT resource_type, severity, COUNT(*) as count
-- FROM log_entries
-- WHERE timestamp > EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - INTERVAL '1 hour') * 1000
--   AND severity IN ('ERROR', 'CRITICAL', 'WARNING')
-- GROUP BY resource_type, severity
-- ORDER BY resource_type, count DESC;

-- Query: log volume trend (last 15 min in 1-min buckets)
-- SELECT (timestamp / 60000) * 60000 as bucket,
--        severity, COUNT(*) as count
-- FROM log_entries
-- WHERE timestamp > EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - INTERVAL '15 minutes') * 1000
-- GROUP BY bucket, severity ORDER BY bucket;
