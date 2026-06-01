-- Secret Manager: Emulator Schema + Sample Data
-- Features: PK hierarchy, BYTEA payload storage, version state machine,
--           labels as JSONB, multi-version secrets with rotation
--
-- Run against LocalCloud PostgreSQL. These tables back the gRPC facade API.

CREATE TABLE IF NOT EXISTS secrets (
    project_id  VARCHAR(255) NOT NULL,
    secret_id   VARCHAR(255) NOT NULL,
    labels      JSONB DEFAULT '{}',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, secret_id)
);

CREATE TABLE IF NOT EXISTS secret_versions (
    project_id      VARCHAR(255) NOT NULL,
    secret_id       VARCHAR(255) NOT NULL,
    version_number  INT NOT NULL,
    payload         BYTEA,
    state           VARCHAR(20) DEFAULT 'ENABLED',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, secret_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_secret_versions_secret
    ON secret_versions (project_id, secret_id);

INSERT INTO secrets (project_id, secret_id, labels) VALUES
    ('local-project', 'db-connection-string', '{"env":"production","team":"platform","rotation":"90d"}'),
    ('local-project', 'api-key-stripe', '{"env":"production","service":"payments","rotation":"180d"}'),
    ('local-project', 'jwt-signing-key', '{"env":"production","service":"auth","rotation":"30d","sensitivity":"critical"}'),
    ('local-project', 'oauth-client-secret', '{"env":"production","service":"auth","provider":"google","sensitivity":"high"}'),
    ('local-project', 'database-encryption-key', '{"env":"production","service":"storage","algorithm":"aes-256-gcm","sensitivity":"critical"}'),
    ('local-project', 'slack-webhook-url', '{"env":"staging","service":"notifications"}'),
    ('local-project', 'github-deploy-key', '{"env":"production","service":"ci-cd","sensitivity":"high"}'),
    ('local-project', 'sentry-dsn', '{"env":"production","service":"monitoring"}'),
    ('local-project', 'redis-password', '{"env":"production","service":"cache","sensitivity":"high"}'),
    ('local-project', 'smtp-password', '{"env":"production","service":"email","sensitivity":"medium"}'),
    ('demo-project', 'demo-api-key', '{"env":"demo","service":"api"}'),
    ('demo-project', 'demo-db-password', '{"env":"demo","service":"database"}')
ON CONFLICT (project_id, secret_id) DO NOTHING;

-- Secrets with version history showing rotation
INSERT INTO secret_versions (project_id, secret_id, version_number, state, created_at) VALUES
    -- db-connection-string: 3 versions (current + 2 previous)
    ('local-project', 'db-connection-string', 1, 'DESTROYED', '2024-11-01T00:00:00Z'),
    ('local-project', 'db-connection-string', 2, 'DISABLED', '2025-02-01T00:00:00Z'),
    ('local-project', 'db-connection-string', 3, 'ENABLED', '2025-05-01T00:00:00Z'),

    -- jwt-signing-key: 5 versions (frequent rotation)
    ('local-project', 'jwt-signing-key', 1, 'DESTROYED', '2024-09-01T00:00:00Z'),
    ('local-project', 'jwt-signing-key', 2, 'DESTROYED', '2024-12-01T00:00:00Z'),
    ('local-project', 'jwt-signing-key', 3, 'DESTROYED', '2025-03-01T00:00:00Z'),
    ('local-project', 'jwt-signing-key', 4, 'DISABLED', '2025-04-01T00:00:00Z'),
    ('local-project', 'jwt-signing-key', 5, 'ENABLED', '2025-05-01T00:00:00Z'),

    -- api-key-stripe: 2 versions
    ('local-project', 'api-key-stripe', 1, 'DISABLED', '2024-06-01T00:00:00Z'),
    ('local-project', 'api-key-stripe', 2, 'ENABLED', '2025-01-01T00:00:00Z'),

    -- oauth-client-secret: 2 versions
    ('local-project', 'oauth-client-secret', 1, 'DISABLED', '2024-08-01T00:00:00Z'),
    ('local-project', 'oauth-client-secret', 2, 'ENABLED', '2025-02-01T00:00:00Z'),

    -- Single-version secrets
    ('local-project', 'database-encryption-key', 1, 'ENABLED', '2024-01-01T00:00:00Z'),
    ('local-project', 'slack-webhook-url', 1, 'ENABLED', '2024-06-15T00:00:00Z'),
    ('local-project', 'github-deploy-key', 1, 'ENABLED', '2024-09-01T00:00:00Z'),
    ('local-project', 'sentry-dsn', 1, 'ENABLED', '2024-03-01T00:00:00Z'),
    ('local-project', 'redis-password', 1, 'ENABLED', '2024-01-15T00:00:00Z'),
    ('local-project', 'smtp-password', 1, 'ENABLED', '2024-04-01T00:00:00Z'),
    ('demo-project', 'demo-api-key', 1, 'ENABLED', '2025-01-01T00:00:00Z'),
    ('demo-project', 'demo-db-password', 1, 'ENABLED', '2025-01-01T00:00:00Z')
ON CONFLICT (project_id, secret_id, version_number) DO NOTHING;

-- Query: find secrets needing rotation (older than 90 days)
-- SELECT s.project_id, s.secret_id,
--        MAX(sv.version_number) as current_version,
--        MAX(sv.created_at) as last_rotation
-- FROM secrets s
-- JOIN secret_versions sv ON s.project_id = sv.project_id AND s.secret_id = sv.secret_id AND sv.state = 'ENABLED'
-- GROUP BY s.project_id, s.secret_id
-- HAVING MAX(sv.created_at) < CURRENT_TIMESTAMP - INTERVAL '90 days';

-- Query: count active secrets per project
-- SELECT project_id, COUNT(*) as active_secrets
-- FROM secrets GROUP BY project_id ORDER BY active_secrets DESC;
