package com.localcloud.license.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SchemaInitializer {

    private static final Logger logger = LoggerFactory.getLogger(SchemaInitializer.class);
    private final DataSource dataSource;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initialize() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id            UUID DEFAULT random_uuid() PRIMARY KEY,
                    email         VARCHAR(255) UNIQUE NOT NULL,
                    email_verified BOOLEAN DEFAULT FALSE,
                    created_at    TIMESTAMP DEFAULT NOW(),
                    status        VARCHAR(50) DEFAULT 'active'
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS otp_codes (
                    id         UUID DEFAULT random_uuid() PRIMARY KEY,
                    email      VARCHAR(255) NOT NULL,
                    code       VARCHAR(10) NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW(),
                    expires_at TIMESTAMP NOT NULL,
                    used       BOOLEAN DEFAULT FALSE
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS api_keys (
                    id         UUID DEFAULT random_uuid() PRIMARY KEY,
                    user_id    UUID REFERENCES users(id),
                    key_hash   VARCHAR(255) NOT NULL,
                    key_prefix VARCHAR(20) NOT NULL,
                    tier       VARCHAR(50) NOT NULL DEFAULT 'community',
                    mode       VARCHAR(20) NOT NULL DEFAULT 'online',
                    created_at TIMESTAMP DEFAULT NOW(),
                    revoked_at TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    id                 UUID DEFAULT random_uuid() PRIMARY KEY,
                    user_id            UUID REFERENCES users(id),
                    device_fingerprint VARCHAR(255) NOT NULL,
                    first_seen         TIMESTAMP DEFAULT NOW(),
                    last_seen          TIMESTAMP DEFAULT NOW(),
                    UNIQUE(user_id, device_fingerprint)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS trials (
                    id                 UUID DEFAULT random_uuid() PRIMARY KEY,
                    user_id            UUID REFERENCES users(id),
                    device_fingerprint VARCHAR(255) NOT NULL UNIQUE,
                    started_at         TIMESTAMP DEFAULT NOW(),
                    expires_at         TIMESTAMP NOT NULL
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    token      VARCHAR(512) PRIMARY KEY,
                    user_id    UUID NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW(),
                    expires_at TIMESTAMP NOT NULL
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)");

            logger.info("License server database schema initialized");
        }
    }
}
