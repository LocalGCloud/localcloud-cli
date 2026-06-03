-- V4__cloud_tasks_queue_config.sql
-- Adds retry config, HTTP target, and rate limit columns to task_queues
-- Adds timing columns to cloud_tasks for better task lifecycle tracking

-- task_queues: retry configuration
ALTER TABLE task_queues ADD COLUMN IF NOT EXISTS min_backoff VARCHAR(20) DEFAULT '0.100s';
ALTER TABLE task_queues ADD COLUMN IF NOT EXISTS max_backoff VARCHAR(20) DEFAULT '3600s';
ALTER TABLE task_queues ADD COLUMN IF NOT EXISTS max_doublings INT DEFAULT 16;
ALTER TABLE task_queues ADD COLUMN IF NOT EXISTS max_retry_duration VARCHAR(20) DEFAULT '0s';

-- task_queues: HTTP target (queue-level default for tasks)
ALTER TABLE task_queues ADD COLUMN IF NOT EXISTS http_target_uri VARCHAR(2000);
ALTER TABLE task_queues ADD COLUMN IF NOT EXISTS http_target_method VARCHAR(10);

-- task_queues: max burst size (rate limits)
ALTER TABLE task_queues ADD COLUMN IF NOT EXISTS max_burst_size INT DEFAULT 0;

-- cloud_tasks: timing and deadline columns
ALTER TABLE cloud_tasks ADD COLUMN IF NOT EXISTS dispatch_deadline TIMESTAMP;
ALTER TABLE cloud_tasks ADD COLUMN IF NOT EXISTS first_attempt_time TIMESTAMP;
ALTER TABLE cloud_tasks ADD COLUMN IF NOT EXISTS last_attempt_time TIMESTAMP;
