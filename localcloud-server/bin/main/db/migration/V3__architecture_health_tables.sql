-- V3__architecture_health_tables.sql
-- New tables added during Architecture Health epic (stories 4, 6)

-- Logging: project-level sinks (Terraform google_logging_project_sink)
CREATE TABLE IF NOT EXISTS logging_sinks (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    sink_id VARCHAR(255) NOT NULL,
    destination VARCHAR(1024) NOT NULL DEFAULT 'bigquery.googleapis.com',
    filter TEXT,
    writer_identity VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, sink_id)
);

-- Monitoring: add policy_id column to existing alert_policies for Terraform compat
ALTER TABLE alert_policies ADD COLUMN IF NOT EXISTS policy_id VARCHAR(255);
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'alert_policies_project_policy_key'
    ) THEN
        ALTER TABLE alert_policies
            ADD CONSTRAINT alert_policies_project_policy_key UNIQUE (project_id, policy_id);
    END IF;
END $$;

-- Legacy: drop deprecated monitoring_alert_policies table
DROP TABLE IF EXISTS monitoring_alert_policies;

-- GKE: node pools (linked to gke_clusters)
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
);

-- Cloud Billing: budgets
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
);
