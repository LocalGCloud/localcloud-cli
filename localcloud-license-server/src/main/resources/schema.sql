CREATE TABLE IF NOT EXISTS users (
    id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    email         VARCHAR(255) UNIQUE NOT NULL,
    email_verified BOOLEAN DEFAULT FALSE,
    created_at    TIMESTAMP DEFAULT NOW(),
    status        VARCHAR(50) DEFAULT 'active'
);

CREATE TABLE IF NOT EXISTS otp_codes (
    id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    code       VARCHAR(10) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL,
    used       BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS api_keys (
    id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id    UUID REFERENCES users(id),
    key_hash   VARCHAR(255) NOT NULL,
    key_prefix VARCHAR(20) NOT NULL,
    tier       VARCHAR(50) NOT NULL DEFAULT 'community',
    mode       VARCHAR(20) NOT NULL DEFAULT 'online',
    created_at TIMESTAMP DEFAULT NOW(),
    revoked_at TIMESTAMP,
    expires_at TIMESTAMP DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id                 UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id            UUID REFERENCES users(id),
    device_fingerprint VARCHAR(255) NOT NULL,
    first_seen         TIMESTAMP DEFAULT NOW(),
    last_seen          TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, device_fingerprint)
);

CREATE TABLE IF NOT EXISTS trials (
    id                 UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id            UUID REFERENCES users(id),
    device_fingerprint VARCHAR(255) NOT NULL UNIQUE,
    started_at         TIMESTAMP DEFAULT NOW(),
    expires_at         TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    token      VARCHAR(512) PRIMARY KEY,
    user_id    UUID NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trials_user ON trials(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS key_pairs (
    id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    key_type   VARCHAR(20) NOT NULL,
    algorithm  VARCHAR(20) NOT NULL,
    private_key TEXT NOT NULL,
    public_key  TEXT NOT NULL,
    kid        VARCHAR(32),
    status     VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    rotated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_key_pairs_type_status ON key_pairs(key_type, status);

CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys(key_hash);
CREATE INDEX IF NOT EXISTS idx_api_keys_user ON api_keys(user_id);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
