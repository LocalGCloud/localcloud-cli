-- V2__add_columns.sql
-- Migration: add columns for existing databases that were added after V1 baseline

-- Cloud Run: traffic splitting support
ALTER TABLE cloudrun_services ADD COLUMN IF NOT EXISTS traffic_json TEXT DEFAULT '[]';

-- Workflow execution: additional fields
ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS call_log_level VARCHAR(30) DEFAULT 'LOG_NONE';
ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS labels JSONB DEFAULT '{}';
ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS status JSONB DEFAULT '{}';
ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS state_error JSONB;
ALTER TABLE workflow_executions ADD COLUMN IF NOT EXISTS duration_ms BIGINT;

-- Workflow: additional metadata fields
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS description TEXT DEFAULT '';
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS call_log_level VARCHAR(30) DEFAULT 'LOG_NONE';
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS execution_history_level VARCHAR(50) DEFAULT 'EXECUTION_HISTORY_BASIC';
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS crypto_key_name VARCHAR(500);
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS user_env_vars JSONB DEFAULT '{}';
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS tags JSONB DEFAULT '{}';

-- Projects: extended metadata
ALTER TABLE projects ADD COLUMN IF NOT EXISTS labels VARCHAR(4096) DEFAULT '{}';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS state VARCHAR(20) DEFAULT 'ACTIVE';
