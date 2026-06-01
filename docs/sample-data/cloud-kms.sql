-- Cloud KMS: Emulator Schema + Sample Data
-- Features: 3-level key hierarchy (key ring -> crypto key -> version),
--           BYTEA key material, algorithm rotation, labels,
--           HSM vs software keys, key states

CREATE TABLE IF NOT EXISTS kms_key_rings (
    project_id   VARCHAR(255) NOT NULL,
    location_id  VARCHAR(255) NOT NULL,
    key_ring_id  VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, key_ring_id)
);

CREATE TABLE IF NOT EXISTS kms_crypto_keys (
    project_id     VARCHAR(255) NOT NULL,
    location_id    VARCHAR(255) NOT NULL,
    key_ring_id    VARCHAR(255) NOT NULL,
    crypto_key_id  VARCHAR(255) NOT NULL,
    purpose        VARCHAR(64) DEFAULT 'ENCRYPT_DECRYPT',
    algorithm      VARCHAR(128) DEFAULT 'GOOGLE_SYMMETRIC_ENCRYPTION',
    protection_level VARCHAR(32) DEFAULT 'SOFTWARE',
    primary_version INT DEFAULT 1,
    rotation_period VARCHAR(32),
    next_rotation   TIMESTAMP,
    labels         JSONB DEFAULT '{}',
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, key_ring_id, crypto_key_id)
);

CREATE TABLE IF NOT EXISTS kms_crypto_key_versions (
    project_id      VARCHAR(255) NOT NULL,
    location_id     VARCHAR(255) NOT NULL,
    key_ring_id     VARCHAR(255) NOT NULL,
    crypto_key_id   VARCHAR(255) NOT NULL,
    version_number  INT NOT NULL,
    state           VARCHAR(32) DEFAULT 'ENABLED',
    algorithm       VARCHAR(128) DEFAULT 'GOOGLE_SYMMETRIC_ENCRYPTION',
    protection_level VARCHAR(32) DEFAULT 'SOFTWARE',
    key_material    BYTEA,
    attestation     JSONB,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, location_id, key_ring_id, crypto_key_id, version_number)
);

INSERT INTO kms_key_rings (project_id, location_id, key_ring_id, created_at) VALUES
    ('local-project', 'global', 'application-keys', '2024-01-01T00:00:00Z'),
    ('local-project', 'global', 'database-keys', '2024-01-15T00:00:00Z'),
    ('local-project', 'us-central1', 'storage-keys', '2024-03-01T00:00:00Z'),
    ('local-project', 'europe-west1', 'compliance-keys', '2024-06-01T00:00:00Z'),
    ('local-project', 'global', 'ci-cd-keys', '2025-01-01T00:00:00Z'),
    ('demo-project', 'global', 'demo-keys', '2025-01-01T00:00:00Z');

INSERT INTO kms_crypto_keys (project_id, location_id, key_ring_id, crypto_key_id, purpose, algorithm, protection_level, primary_version, rotation_period, next_rotation, labels) VALUES
    ('local-project', 'global', 'application-keys', 'app-secret-key', 'ENCRYPT_DECRYPT', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', 2, '7776000s', '2025-08-01T00:00:00Z',
     '{"env":"production","service":"app-backend","team":"platform"}'),

    ('local-project', 'global', 'application-keys', 'config-signing-key', 'ASYMMETRIC_SIGN', 'EC_SIGN_P256_SHA256', 'SOFTWARE', 1, NULL, NULL,
     '{"env":"production","service":"config-service","algorithm":"ecdsa-p256"}'),

    ('local-project', 'global', 'database-keys', 'db-at-rest-key', 'ENCRYPT_DECRYPT', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', 1, '2592000s', '2025-06-15T00:00:00Z',
     '{"env":"production","service":"database","type":"aes-256-gcm"}'),

    ('local-project', 'global', 'database-keys', 'db-backup-key', 'ENCRYPT_DECRYPT', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'HSM', 1, '31536000s', '2026-01-01T00:00:00Z',
     '{"env":"production","service":"database-backup","protection":"hsm","compliance":"soc2"}'),

    ('local-project', 'us-central1', 'storage-keys', 'gcs-encryption-key', 'ENCRYPT_DECRYPT', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', 2, '7776000s', '2025-07-01T00:00:00Z',
     '{"env":"production","service":"cloud-storage","bucket":"user-profiles"}'),

    ('local-project', 'europe-west1', 'compliance-keys', 'pii-encryption-key', 'ENCRYPT_DECRYPT', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'HSM', 1, '7776000s', '2025-08-01T00:00:00Z',
     '{"env":"production","service":"compliance","regulation":"gdpr","protection":"hsm"}'),

    ('local-project', 'global', 'ci-cd-keys', 'deploy-signing-key', 'ASYMMETRIC_SIGN', 'RSA_SIGN_PSS_4096_SHA512', 'SOFTWARE', 1, NULL, NULL,
     '{"env":"production","service":"ci-cd","purpose":"artifact-signing"}'),

    ('local-project', 'global', 'ci-cd-keys', 'container-scanning-key', 'ASYMMETRIC_SIGN', 'EC_SIGN_P384_SHA384', 'SOFTWARE', 1, NULL, NULL,
     '{"env":"production","service":"container-scanning"}'),

    ('local-project', 'global', 'application-keys', 'deprecated-key-v1', 'ENCRYPT_DECRYPT', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', 1, NULL, NULL,
     '{"env":"deprecated","service":"legacy","note":"Migrate to app-secret-key"}'),

    ('demo-project', 'global', 'demo-keys', 'demo-key', 'ENCRYPT_DECRYPT', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', 1, NULL, NULL,
     '{"env":"demo"}');

-- Key versions with rotation history
INSERT INTO kms_crypto_key_versions (project_id, location_id, key_ring_id, crypto_key_id, version_number, state, algorithm, protection_level, created_at) VALUES
    -- app-secret-key: 2 versions (v1 disabled, v2 primary)
    ('local-project', 'global', 'application-keys', 'app-secret-key', 1, 'DISABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', '2024-01-01T00:00:00Z'),
    ('local-project', 'global', 'application-keys', 'app-secret-key', 2, 'ENABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', '2025-01-01T00:00:00Z'),

    -- gcs-encryption-key: 2 versions (v1 destroyed, v2 primary)
    ('local-project', 'us-central1', 'storage-keys', 'gcs-encryption-key', 1, 'DESTROYED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', '2024-03-01T00:00:00Z'),
    ('local-project', 'us-central1', 'storage-keys', 'gcs-encryption-key', 2, 'ENABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', '2025-01-01T00:00:00Z'),

    -- Others: single version
    ('local-project', 'global', 'application-keys', 'config-signing-key', 1, 'ENABLED', 'EC_SIGN_P256_SHA256', 'SOFTWARE', '2024-01-15T00:00:00Z'),
    ('local-project', 'global', 'database-keys', 'db-at-rest-key', 1, 'ENABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', '2024-01-15T00:00:00Z'),
    ('local-project', 'global', 'database-keys', 'db-backup-key', 1, 'ENABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'HSM', '2024-03-01T00:00:00Z'),
    ('local-project', 'europe-west1', 'compliance-keys', 'pii-encryption-key', 1, 'ENABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'HSM', '2024-06-01T00:00:00Z'),
    ('local-project', 'global', 'ci-cd-keys', 'deploy-signing-key', 1, 'ENABLED', 'RSA_SIGN_PSS_4096_SHA512', 'SOFTWARE', '2025-01-01T00:00:00Z'),
    ('local-project', 'global', 'ci-cd-keys', 'container-scanning-key', 1, 'ENABLED', 'EC_SIGN_P384_SHA384', 'SOFTWARE', '2025-01-01T00:00:00Z'),
    ('local-project', 'global', 'application-keys', 'deprecated-key-v1', 1, 'DISABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', '2024-01-01T00:00:00Z'),
    ('demo-project', 'global', 'demo-keys', 'demo-key', 1, 'ENABLED', 'GOOGLE_SYMMETRIC_ENCRYPTION', 'SOFTWARE', '2025-01-01T00:00:00Z');

-- Query: key rotation status
-- SELECT c.crypto_key_id, c.rotation_period, c.next_rotation,
--        MAX(v.version_number) as current_version,
--        MAX(v.created_at) as last_rotation
-- FROM kms_crypto_keys c
-- LEFT JOIN kms_crypto_key_versions v ON c.project_id = v.project_id
--     AND c.location_id = v.location_id
--     AND c.key_ring_id = v.key_ring_id
--     AND c.crypto_key_id = v.crypto_key_id
--     AND v.state = 'ENABLED'
-- WHERE c.project_id = 'local-project'
-- GROUP BY c.crypto_key_id, c.rotation_period, c.next_rotation;

-- Query: protection level distribution
-- SELECT protection_level, COUNT(*) as key_count
-- FROM kms_crypto_keys WHERE project_id = 'local-project'
-- GROUP BY protection_level;
