-- Bigtable: Comprehensive DDL + Sample Data
-- Features: FAMILY definitions with MAX_VERSIONS, column families,
--           row key design patterns, cells with timestamps,
--           ALTER TABLE ADD/DROP FAMILY
--
-- Use the LocalCloud Bigtable SQL Console or the Bigtable SQL dialect parser.
-- Table references use the format "instance.table_name"

-- ============================================================
-- USER ACTIVITY TABLE (Wide-column design)
-- ============================================================

CREATE TABLE "local-instance.user_activity" (
  FAMILY profile MAX_VERSIONS 5,
  FAMILY activity MAX_VERSIONS 100,
  FAMILY sessions MAX_VERSIONS 365,
  FAMILY recommendations MAX_VERSIONS 10
);

-- ============================================================
-- TIME-SERIES METRICS TABLE (High cardinality)
-- ============================================================

CREATE TABLE "local-instance.metrics" (
  FAMILY cpu MAX_VERSIONS 10000,
  FAMILY memory MAX_VERSIONS 10000,
  FAMILY disk MAX_VERSIONS 10000,
  FAMILY network MAX_VERSIONS 10000,
  FAMILY custom MAX_VERSIONS 50000
);

-- ============================================================
-- EVENT LOG TABLE (Appending time-ordered events)
-- ============================================================

CREATE TABLE "local-instance.event_log" (
  FAMILY event MAX_VERSIONS 1000,
  FAMILY context MAX_VERSIONS 1000,
  FAMILY metadata MAX_VERSIONS 1000
);

-- ============================================================
-- INSERT SAMPLE DATA
-- ============================================================

-- User activity: 10 users with profile + activity + sessions
INSERT INTO "local-instance.user_activity" (
  rowkey,
  profile:name,
  profile:email,
  profile:role,
  profile:tier,
  profile:company,
  profile:created,
  activity:last_login,
  activity:total_views,
  activity:total_sessions,
  activity:avg_session_duration,
  activity:feature_usage,
  activity:last_ip,
  sessions:last_session_date,
  sessions:session_count_7d,
  sessions:session_count_30d,
  recommendations:last_genre,
  recommendations:last_product
) VALUES
  ('user#0001', 'Alice Johnson', 'alice@example.com', 'admin', 'enterprise',
   'Acme Corp', '2024-01-15T08:30:00Z',
   '2025-05-20T14:22:00Z', '342', '56', '480',
   '{"dashboard":45,"reports":23,"admin":12}',
   '192.168.1.100', '2025-05-20', '12', '45',
   'analytics', 'prod-007'),
  ('user#0002', 'Bob Smith', 'bob@techcorp.com', 'user', 'pro',
   'TechCorp', '2024-02-20T10:00:00Z',
   '2025-05-19T09:15:00Z', '218', '41', '320',
   '{"dashboard":30,"reports":8,"deploy":15}',
   '10.0.0.50', '2025-05-19', '8', '30',
   'devops', 'prod-004'),
  ('user#0003', 'Carol Davis', 'carol@dataflow.com', 'editor', 'pro',
   'DataFlow Inc', '2024-03-10T14:00:00Z',
   '2025-05-20T11:30:00Z', '289', '47', '550',
   '{"datasets":35,"queries":40,"dashboards":18}',
   '172.16.0.25', '2025-05-20', '11', '42',
   'data-science', 'prod-014'),
  ('user#0004', 'David Chen', 'david@cloudbase.io', 'admin', 'enterprise',
   'CloudBase', '2024-01-05T09:00:00Z',
   '2025-05-20T16:45:00Z', '412', '63', '620',
   '{"infra":50,"security":28,"billing":15}',
   '203.0.113.10', '2025-05-20', '15', '52',
   'cloud', 'prod-015'),
  ('user#0005', 'Emma Wilson', 'emma@devopspro.com', 'editor', 'standard',
   'DevOps Pro', '2024-04-01T11:00:00Z',
   '2025-05-18T08:00:00Z', '175', '32', '280',
   '{"deploy":20,"monitoring":25,"logs":10}',
   '198.51.100.5', '2025-05-18', '6', '22',
   'cicd', 'prod-004'),
  ('user#0006', 'Frank Miller', 'frank@securenet.com', 'user', 'standard',
   'SecureNet', '2024-05-12T16:30:00Z',
   '2025-05-17T13:20:00Z', '134', '22', '190',
   '{"security":28,"audit":15,"compliance":8}',
   '192.0.2.15', '2025-05-17', '4', '15',
   'security', 'prod-010'),
  ('user#0007', 'Grace Lee', 'grace@ailabs.com', 'editor', 'pro',
   'AI Labs', '2024-03-22T08:45:00Z',
   '2025-05-20T10:10:00Z', '267', '44', '520',
   '{"models":30,"training":35,"inference":25}',
   '10.10.0.100', '2025-05-20', '10', '38',
   'ml', 'prod-014'),
  ('user#0008', 'Henry Park', 'henry@fintechco.com', 'user', 'pro',
   'FinTech Co', '2024-06-01T13:00:00Z',
   '2025-05-19T15:30:00Z', '198', '35', '380',
   '{"transactions":40,"reports":22,"alerts":12}',
   '10.20.0.50', '2025-05-19', '7', '28',
   'fintech', 'prod-010'),
  ('user#0009', 'Ivy Zhang', 'ivy@webscale.io', 'admin', 'enterprise',
   'WebScale', '2023-11-10T10:00:00Z',
   '2025-05-20T17:00:00Z', '523', '78', '750',
   '{"platform":65,"cost":42,"compliance":30}',
   '203.0.113.200', '2025-05-20', '18', '65',
   'scaling', 'prod-014'),
  ('user#0010', 'Jack Brown', 'jack@startup.io', 'user', 'free',
   'StartupIO', '2025-01-20T09:00:00Z',
   '2025-05-15T10:00:00Z', '45', '8', '120',
   '{"dashboard":15,"settings":5}',
   '198.51.100.99', '2025-05-15', '2', '5',
   'startup', 'prod-001');

-- Metrics: 5 services, 5 data points each (25 rows)
INSERT INTO "local-instance.metrics" (
  rowkey,
  cpu:usage, cpu:temperature,
  memory:used_mb, memory:available_mb, memory:percent,
  disk:read_bytes, disk:write_bytes, disk:iops,
  network:in_bytes, network:out_bytes, network:connections,
  custom:latency_p50, custom:latency_p99, custom:error_rate, custom:request_count
) VALUES
  ('service#api-gateway#2025-05-20T08:00:00Z', '45.2', '72.1', '2048', '3072', '40', '1.5e9', '0.8e9', '1200', '500000000', '300000000', '1200', '45', '120', '0.001', '50000'),
  ('service#api-gateway#2025-05-20T08:01:00Z', '48.7', '72.5', '2100', '3020', '41', '1.6e9', '0.9e9', '1250', '520000000', '310000000', '1250', '48', '135', '0.002', '52000'),
  ('service#api-gateway#2025-05-20T08:02:00Z', '52.1', '73.0', '2200', '2920', '43', '1.8e9', '1.0e9', '1300', '550000000', '340000000', '1300', '52', '150', '0.003', '55000'),
  ('service#api-gateway#2025-05-20T08:03:00Z', '55.3', '73.4', '2350', '2770', '46', '2.0e9', '1.1e9', '1350', '580000000', '360000000', '1350', '55', '165', '0.004', '58000'),
  ('service#api-gateway#2025-05-20T08:04:00Z', '50.8', '72.8', '2250', '2870', '44', '1.9e9', '1.0e9', '1280', '540000000', '330000000', '1280', '50', '140', '0.002', '54000'),
  ('service#user-service#2025-05-20T08:00:00Z', '22.1', '65.2', '1024', '4096', '20', '0.2e9', '0.5e9', '400', '100000000', '200000000', '400', '22', '60', '0.0005', '20000'),
  ('service#user-service#2025-05-20T08:01:00Z', '24.5', '65.5', '1100', '4020', '21', '0.3e9', '0.6e9', '420', '110000000', '220000000', '420', '25', '65', '0.0008', '22000'),
  ('service#user-service#2025-05-20T08:02:00Z', '28.3', '66.1', '1200', '3920', '23', '0.4e9', '0.7e9', '450', '130000000', '240000000', '450', '28', '70', '0.001', '25000'),
  ('service#user-service#2025-05-20T08:03:00Z', '26.7', '65.8', '1150', '3970', '22', '0.35e9', '0.65e9', '430', '120000000', '230000000', '430', '26', '68', '0.0009', '23000'),
  ('service#user-service#2025-05-20T08:04:00Z', '23.8', '65.4', '1080', '4040', '21', '0.28e9', '0.55e9', '410', '105000000', '210000000', '410', '24', '62', '0.0006', '21000'),
  ('service#order-service#2025-05-20T08:00:00Z', '35.6', '68.5', '1536', '3584', '30', '0.5e9', '1.2e9', '600', '200000000', '150000000', '200', '35', '90', '0.002', '8000'),
  ('service#order-service#2025-05-20T08:01:00Z', '38.2', '69.0', '1600', '3520', '31', '0.6e9', '1.3e9', '650', '220000000', '160000000', '220', '38', '95', '0.003', '8500'),
  ('service#order-service#2025-05-20T08:02:00Z', '42.5', '69.8', '1700', '3420', '33', '0.7e9', '1.5e9', '700', '250000000', '180000000', '250', '42', '105', '0.005', '9000'),
  ('service#order-service#2025-05-20T08:03:00Z', '40.1', '69.3', '1650', '3470', '32', '0.65e9', '1.4e9', '680', '240000000', '170000000', '240', '40', '100', '0.004', '8800'),
  ('service#order-service#2025-05-20T08:04:00Z', '36.8', '68.7', '1580', '3540', '31', '0.55e9', '1.25e9', '620', '210000000', '155000000', '210', '37', '92', '0.002', '8200'),
  ('service#payment-worker#2025-05-20T08:00:00Z', '65.8', '78.2', '3072', '2048', '60', '2.5e9', '3.0e9', '2500', '800000000', '600000000', '100', '120', '350', '0.015', '12000'),
  ('service#payment-worker#2025-05-20T08:01:00Z', '68.2', '78.8', '3200', '1920', '62', '2.8e9', '3.2e9', '2600', '850000000', '620000000', '110', '125', '380', '0.018', '12500'),
  ('service#payment-worker#2025-05-20T08:02:00Z', '72.5', '79.5', '3350', '1770', '65', '3.0e9', '3.5e9', '2800', '900000000', '650000000', '120', '135', '420', '0.022', '13000'),
  ('service#payment-worker#2025-05-20T08:03:00Z', '70.1', '79.0', '3250', '1870', '63', '2.9e9', '3.3e9', '2700', '870000000', '640000000', '115', '130', '400', '0.020', '12800'),
  ('service#payment-worker#2025-05-20T08:04:00Z', '67.5', '78.5', '3100', '2020', '61', '2.6e9', '3.1e9', '2550', '820000000', '610000000', '105', '122', '360', '0.016', '12300'),
  ('service#ml-service#2025-05-20T08:00:00Z', '92.5', '85.5', '8192', '2048', '80', '1.0e9', '8.0e9', '5000', '100000000', '500000000', '50', '250', '800', '0.008', '5000'),
  ('service#ml-service#2025-05-20T08:01:00Z', '94.2', '86.0', '8400', '1840', '82', '1.2e9', '8.5e9', '5200', '110000000', '520000000', '52', '260', '850', '0.009', '5200'),
  ('service#ml-service#2025-05-20T08:02:00Z', '95.8', '86.8', '8600', '1640', '84', '1.5e9', '9.0e9', '5500', '120000000', '550000000', '55', '275', '900', '0.010', '5500'),
  ('service#ml-service#2025-05-20T08:03:00Z', '93.5', '85.8', '8300', '1940', '81', '1.1e9', '8.2e9', '5100', '105000000', '510000000', '51', '255', '820', '0.008', '5100'),
  ('service#ml-service#2025-05-20T08:04:00Z', '91.8', '85.2', '8000', '2240', '78', '0.9e9', '7.8e9', '4800', '95000000', '480000000', '48', '245', '780', '0.007', '4800');

-- Event log: 12 sample events
INSERT INTO "local-instance.event_log" (
  rowkey,
  event:type, event:severity, event:source, event:message, event:timestamp,
  context:service, context:host, context:region,
  metadata:trace_id, metadata:user_id, metadata:extra
) VALUES
  ('evt#2025-05-20#00001', 'deploy', 'info', 'argo-cd', 'Deployment rollout completed for api-gateway v2.1.0', '2025-05-20T08:00:00Z', 'api-gateway', 'k8s-node-01', 'us-central1', 'trace-abc-001', 'user-001', '{"replicas":6,"strategy":"rolling"}'),
  ('evt#2025-05-20#00002', 'error', 'critical', 'payment-worker', 'Payment processing timeout after 30s for order ord-2025-100', '2025-05-20T08:01:15Z', 'payment-worker', 'k8s-node-02', 'us-east1', 'trace-abc-002', 'user-002', '{"orderId":"ord-2025-100","timeout":30000,"retry":2}'),
  ('evt#2025-05-20#00003', 'alert', 'warning', 'prometheus', 'CPU threshold exceeded on payment-worker: 92% for 5m', '2025-05-20T08:02:00Z', 'payment-worker', 'k8s-node-02', 'us-east1', 'trace-abc-003', NULL, '{"threshold":80,"current":92,"duration":"5m"}'),
  ('evt#2025-05-20#00004', 'metric', 'info', 'cloudwatch', 'API Gateway p99 latency spike detected: 420ms', '2025-05-20T08:03:30Z', 'api-gateway', 'k8s-node-01', 'us-central1', 'trace-abc-004', NULL, '{"metric":"p99_latency","value":420,"baseline":120}'),
  ('evt#2025-05-20#00005', 'auth', 'warn', 'keycloak', 'Failed login attempt for user-010 from IP 45.33.32.156', '2025-05-20T08:05:00Z', 'auth-service', 'k8s-node-03', 'eu-west1', 'trace-abc-005', 'user-010', '{"ip":"45.33.32.156","attempts":3,"window":"5m"}'),
  ('evt#2025-05-20#00006', 'deploy', 'info', 'argo-cd', 'New revision deployed for user-service v3.2.1', '2025-05-20T09:00:00Z', 'user-service', 'k8s-node-01', 'us-central1', 'trace-abc-006', 'user-001', '{"replicas":4,"strategy":"blue-green"}'),
  ('evt#2025-05-20#00007', 'scaling', 'info', 'hpa', 'Horizontal scaling triggered: order-service 3->5 replicas', '2025-05-20T09:15:00Z', 'order-service', 'k8s-node-04', 'us-west1', 'trace-abc-007', NULL, '{"from":3,"to":5,"metric":"cpu","current":78}'),
  ('evt#2025-05-20#00008', 'error', 'error', 'database', 'Connection pool exhausted on alloydb-primary: 100% utilization', '2025-05-20T09:30:00Z', 'alloydb-primary', 'db-01', 'us-central1', 'trace-abc-008', NULL, '{"pool":100,"maxConnections":200,"queries":["SELECT..."]}'),
  ('evt#2025-05-20#00009', 'backup', 'info', 'velero', 'Daily backup completed for etcd: 2.5GB in 45s', '2025-05-20T10:00:00Z', 'etcd-backup', 'k8s-node-01', 'us-central1', 'trace-abc-009', 'system', '{"size":"2.5GB","duration":45,"type":"snapshot"}'),
  ('evt#2025-05-20#00010', 'audit', 'info', 'cloud-audit', 'IAM policy updated for project local-project by admin@localcloud', '2025-05-20T11:00:00Z', 'iam', 'gateway-01', 'global', 'trace-abc-010', 'admin@localcloud', '{"action":"policy_update","resource":"projects/local-project"}'),
  ('evt#2025-05-20#00011', 'performance', 'warn', 'datadog', 'ML training job train-2025-05-20 exceeded estimated cost by 150%', '2025-05-20T12:00:00Z', 'ml-service', 'gpu-node-01', 'us-central1', 'trace-abc-011', 'user-007', '{"job":"train-2025-05-20","estimated":100,"actual":250,"gpuHours":45}'),
  ('evt#2025-05-20#00012', 'compliance', 'info', 'scc', 'Compliance scan completed: 0 critical, 2 high, 5 medium findings', '2025-05-20T13:00:00Z', 'security-center', 'scanner-01', 'global', 'trace-abc-012', 'system', '{"critical":0,"high":2,"medium":5,"low":12}');

-- ALTER TABLE examples (uncomment to run):
-- ALTER TABLE "local-instance.user_activity" ADD FAMILY notifications MAX_VERSIONS 50;
-- ALTER TABLE "local-instance.user_activity" DROP FAMILY recommendations;

-- Query examples (uncomment to run):
-- SELECT rowkey, profile:name, profile:email, activity:last_login
-- FROM "local-instance.user_activity"
-- WHERE activity:total_views > 200
-- ORDER BY activity:total_views DESC LIMIT 10;
--
-- SELECT * FROM "local-instance.metrics"
-- WHERE cpu:usage > 80
-- LIMIT 5;
--
-- SELECT rowkey, event:message, event:severity
-- FROM "local-instance.event_log"
-- WHERE event:severity IN ('critical', 'error')
-- ORDER BY rowkey;
