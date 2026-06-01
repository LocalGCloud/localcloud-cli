-- Memorystore (Redis/Valkey): Emulator Schema + Sample Data
-- Features: admin API instances, redis_data for all 5 data types,
--           JSONB for instance config, tier variants
--
-- Run against LocalCloud PostgreSQL.

CREATE TABLE IF NOT EXISTS memorystore_instances (
    project_id     VARCHAR(255) NOT NULL,
    instance_id    VARCHAR(255) NOT NULL,
    display_name   VARCHAR(255),
    tier           VARCHAR(32) DEFAULT 'BASIC',
    engine         VARCHAR(32) DEFAULT 'REDIS',
    redis_version  VARCHAR(32) DEFAULT '7_0',
    port           INT DEFAULT 6379,
    memory_size_gb INT DEFAULT 1,
    state          VARCHAR(32) DEFAULT 'READY',
    host           VARCHAR(255) DEFAULT 'localhost',
    replica_count  INT DEFAULT 0,
    shard_count    INT DEFAULT 1,
    auth_enabled   BOOLEAN DEFAULT FALSE,
    persistence_mode VARCHAR(32) DEFAULT 'DISABLED',
    connect_mode   VARCHAR(32) DEFAULT 'DIRECT_PEERING',
    labels_json    JSONB DEFAULT '{}',
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, instance_id)
);

-- The redis_data table is managed by the external Netty codec-redis RESP2 server.
-- This schema is representative:
-- CREATE TABLE IF NOT EXISTS redis_data (
--     project_id  VARCHAR(255) NOT NULL DEFAULT 'local-project',
--     key_name    VARCHAR(1024) NOT NULL,
--     value_type  VARCHAR(16) NOT NULL DEFAULT 'string',
--     value_data  TEXT,
--     hash_fields JSONB DEFAULT '{}',
--     list_items  JSONB DEFAULT '[]',
--     set_members JSONB DEFAULT '[]',
--     zset_members JSONB DEFAULT '[]',
--     ttl_ms      BIGINT DEFAULT -1,
--     created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     PRIMARY KEY (project_id, key_name)
-- );

INSERT INTO memorystore_instances (project_id, instance_id, display_name, tier, engine, redis_version, port, memory_size_gb, state, host, replica_count, shard_count, auth_enabled, persistence_mode, labels_json, created_at) VALUES
    ('local-project', 'session-cache', 'Session Cache', 'BASIC', 'REDIS', '7_2', 6379, 2, 'READY', 'localhost', 0, 1, FALSE, 'DISABLED',
     '{"env":"production","team":"platform","use":"sessions"}', '2024-01-15T08:00:00Z'),

    ('local-project', 'api-cache', 'API Response Cache', 'STANDARD_HA', 'REDIS', '7_2', 6380, 10, 'READY', 'localhost', 2, 2, TRUE, 'AOF',
     '{"env":"production","team":"backend","use":"api-caching","critical":"true"}', '2024-03-01T10:00:00Z'),

    ('local-project', 'rate-limiter', 'Rate Limiter', 'BASIC', 'REDIS', '7_0', 6381, 1, 'READY', 'localhost', 0, 1, FALSE, 'DISABLED',
     '{"env":"production","team":"platform","use":"rate-limiting"}', '2024-06-01T14:00:00Z'),

    ('local-project', 'queue-store', 'Background Job Queue', 'STANDARD_HA', 'VALKEY', '7_2', 6382, 20, 'READY', 'localhost', 3, 3, TRUE, 'RDB',
     '{"env":"production","team":"backend","use":"job-queues","critical":"true"}', '2024-04-15T09:00:00Z'),

    ('local-project', 'analytics-cache', 'Analytics Cache', 'STANDARD_HA', 'REDIS', '7_2', 6383, 50, 'CREATE_FAILED', 'localhost', 2, 5, TRUE, 'AOF',
     '{"env":"staging","team":"data","use":"analytics"}', '2025-05-20T08:00:00Z'),

    ('local-project', 'dev-cache', 'Dev Cache', 'BASIC', 'REDIS', '7_0', 6384, 1, 'READY', 'localhost', 0, 1, FALSE, 'DISABLED',
     '{"env":"development","team":"engineering"}', '2024-02-01T08:00:00Z'),

    ('demo-project', 'demo-cache', 'Demo Cache', 'BASIC', 'REDIS', '7_0', 6385, 1, 'READY', 'localhost', 0, 1, FALSE, 'DISABLED',
     '{"env":"demo","team":"sales"}', '2025-01-01T00:00:00Z');

-- Query: memory capacity by tier
-- SELECT tier, SUM(memory_size_gb) as total_gb, COUNT(*) as instances
-- FROM memorystore_instances WHERE state = 'READY'
-- GROUP BY tier ORDER BY total_gb DESC;

-- Query: high-availability coverage
-- SELECT engine, COUNT(*) as instances,
--        SUM(CASE WHEN tier = 'STANDARD_HA' THEN 1 ELSE 0 END) as ha_count,
--        SUM(CASE WHEN auth_enabled THEN 1 ELSE 0 END) as auth_count
-- FROM memorystore_instances
-- GROUP BY engine;
