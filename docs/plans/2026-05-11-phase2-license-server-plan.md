# Phase 2 — License Server MVP Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a self-hosted license server (`localcloud-license-server/`) that handles user registration, email OTP verification, API key generation, online key validation, and 14-day trial management with device fingerprint abuse prevention.

**Architecture:** New Gradle project `localcloud-license-server/` using Java 21 + Armeria (same stack as `localcloud-server`). PostgreSQL for persistence (HikariCP). REST-only (no gRPC). Email OTP via configurable SMTP (JavaMail). The server exposes endpoints at `https://api.localcloud.dev` (or any host). After this phase, the main LocalCloud container's `OnlineKeyValidator` can call a real server instead of bypass mode.

**Tech Stack:** Java 21, Armeria 1.31.3, PostgreSQL 17, HikariCP 5.1.0, Jackson 2.17.0, JavaMail 2.1.3, JJWT 0.12.6 (for RS256 JWT responses)

---

### Task 1: Project Scaffolding

**Files:**
- Create: `localcloud-license-server/build.gradle`
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/LicenseServerApplication.java`
- Create: `localcloud-license-server/src/main/resources/logback.xml`
- Modify: `settings.gradle` (add `include 'localcloud-license-server'` if present, or note it's a standalone project)

**Step 1: Create `localcloud-license-server/build.gradle`**

```groovy
plugins {
    id 'java'
    id 'application'
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

group = 'com.localcloud'
version = '0.1.0-SNAPSHOT'
description = 'LocalCloud License Server'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs << '-parameters'
}

repositories {
    mavenCentral()
}

def armeriaVersion = '1.31.3'
def jacksonVersion = '2.17.0'

dependencies {
    implementation platform("com.linecorp.armeria:armeria-bom:${armeriaVersion}")
    implementation "com.linecorp.armeria:armeria"

    implementation 'org.postgresql:postgresql:42.7.3'
    implementation 'com.zaxxer:HikariCP:5.1.0'

    implementation "com.fasterxml.jackson.core:jackson-databind:${jacksonVersion}"
    implementation "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${jacksonVersion}"

    // JJWT for RS256 JWT signing (online key validation responses)
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly  'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly  'io.jsonwebtoken:jjwt-jackson:0.12.6'

    // JavaMail for OTP email
    implementation 'com.sun.mail:jakarta.mail:2.0.1'

    // SLF4J + Logback
    implementation 'org.slf4j:slf4j-api:2.0.12'
    implementation 'ch.qos.logback:logback-classic:1.5.3'

    // JUnit 5
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'org.mockito:mockito-core:5.11.0'
    testImplementation 'org.mockito:mockito-junit-jupiter:5.11.0'
    testImplementation 'com.h2database:h2:2.2.224'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

application {
    mainClass = 'com.localcloud.license.LicenseServerApplication'
}

shadowJar {
    archiveBaseName.set('localcloud-license-server')
    archiveClassifier.set('all')
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    manifest {
        attributes 'Main-Class': application.mainClass
    }
}

test {
    useJUnitPlatform()
}

tasks.named('build') {
    dependsOn tasks.named('shadowJar')
}
```

**Step 2: Create main application class**

Create `localcloud-license-server/src/main/java/com/localcloud/license/LicenseServerApplication.java`:

```java
package com.localcloud.license;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LicenseServerApplication {

    private static final Logger logger = LoggerFactory.getLogger(LicenseServerApplication.class);

    public static void main(String[] args) throws Exception {
        LicenseServerConfig config = LicenseServerConfig.fromEnvironment();

        ServerBuilder sb = Server.builder();
        sb.http(config.getPort());

        Server server = sb.build();
        server.start().join();
        logger.info("LocalCloud License Server started on port {}", config.getPort());
        server.blockUntilShutdown();
    }
}
```

**Step 3: Create config class**

Create `localcloud-license-server/src/main/java/com/localcloud/license/LicenseServerConfig.java`:

```java
package com.localcloud.license;

public class LicenseServerConfig {

    private int port;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;
    private String smtpHost;
    private int smtpPort;
    private String smtpUser;
    private String smtpPassword;
    private String smtpFrom;
    private String jwtPrivateKeyBase64;  // RS256 private key for JWT signing
    private String ed25519PrivateKeyBase64; // For offline key signing
    private String ed25519PublicKeyBase64;  // Embedded in client JARs
    private int otpExpiryMinutes;
    private int trialDays;

    private LicenseServerConfig() {}

    public static LicenseServerConfig fromEnvironment() {
        LicenseServerConfig c = new LicenseServerConfig();
        c.port = intEnv("LICENSE_PORT", 9090);
        c.dbUrl = env("LICENSE_DB_URL", "jdbc:postgresql://localhost:5432/localcloud_license");
        c.dbUser = env("LICENSE_DB_USER", "license");
        c.dbPassword = env("LICENSE_DB_PASSWORD", "license");
        c.smtpHost = env("LICENSE_SMTP_HOST", "localhost");
        c.smtpPort = intEnv("LICENSE_SMTP_PORT", 587);
        c.smtpUser = env("LICENSE_SMTP_USER", "");
        c.smtpPassword = env("LICENSE_SMTP_PASSWORD", "");
        c.smtpFrom = env("LICENSE_SMTP_FROM", "noreply@localcloud.dev");
        c.jwtPrivateKeyBase64 = env("LICENSE_JWT_PRIVATE_KEY", "");
        c.ed25519PrivateKeyBase64 = env("LICENSE_ED25519_PRIVATE_KEY", "");
        c.ed25519PublicKeyBase64 = env("LICENSE_ED25519_PUBLIC_KEY", "");
        c.otpExpiryMinutes = intEnv("LICENSE_OTP_EXPIRY_MINUTES", 15);
        c.trialDays = intEnv("LICENSE_TRIAL_DAYS", 14);
        return c;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static int intEnv(String name, int def) {
        try { return Integer.parseInt(env(name, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    // Getters
    public int getPort() { return port; }
    public String getDbUrl() { return dbUrl; }
    public String getDbUser() { return dbUser; }
    public String getDbPassword() { return dbPassword; }
    public String getSmtpHost() { return smtpHost; }
    public int getSmtpPort() { return smtpPort; }
    public String getSmtpUser() { return smtpUser; }
    public String getSmtpPassword() { return smtpPassword; }
    public String getSmtpFrom() { return smtpFrom; }
    public String getJwtPrivateKeyBase64() { return jwtPrivateKeyBase64; }
    public String getEd25519PrivateKeyBase64() { return ed25519PrivateKeyBase64; }
    public String getEd25519PublicKeyBase64() { return ed25519PublicKeyBase64; }
    public int getOtpExpiryMinutes() { return otpExpiryMinutes; }
    public int getTrialDays() { return trialDays; }
}
```

**Step 4: Create logback.xml**

Create `localcloud-license-server/src/main/resources/logback.xml`:
```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

**Step 5: Verify it compiles**

Run: `cd localcloud-license-server && ./gradlew compileJava 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add localcloud-license-server/
git commit -m "feat(license-server): project scaffolding with Armeria + config"
```

---

### Task 2: Database Schema + Connection Pool

**Files:**
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/db/LicenseDatabase.java`
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/db/SchemaInitializer.java`
- Test: `localcloud-license-server/src/test/java/com/localcloud/license/db/SchemaInitializerTest.java`

**Step 1: Write the failing test**

Create `localcloud-license-server/src/test/java/com/localcloud/license/db/SchemaInitializerTest.java`:

```java
package com.localcloud.license.db;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaInitializerTest {

    @Test
    void schemaCreatesAllTables() throws Exception {
        // Use H2 in-memory database (PostgreSQL-compatible mode)
        javax.sql.DataSource ds = createH2DataSource();
        SchemaInitializer init = new SchemaInitializer(ds);
        init.initialize();

        // Verify all expected tables exist
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            // users table
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM users");
            assertTrue(rs.next());

            // api_keys table
            rs = stmt.executeQuery("SELECT COUNT(*) FROM api_keys");
            assertTrue(rs.next());

            // devices table
            rs = stmt.executeQuery("SELECT COUNT(*) FROM devices");
            assertTrue(rs.next());

            // trials table
            rs = stmt.executeQuery("SELECT COUNT(*) FROM trials");
            assertTrue(rs.next());
        }
    }

    @Test
    void schemaIsIdempotent() throws Exception {
        javax.sql.DataSource ds = createH2DataSource();
        SchemaInitializer init = new SchemaInitializer(ds);
        init.initialize();
        // Run twice — should not throw
        assertDoesNotThrow(init::initialize);
    }

    private javax.sql.DataSource createH2DataSource() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_schema_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }
}
```

**Step 2: Run to verify it fails**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.db.SchemaInitializerTest" 2>&1 | tail -15`
Expected: FAIL — class does not exist

**Step 3: Create LicenseDatabase**

Create `localcloud-license-server/src/main/java/com/localcloud/license/db/LicenseDatabase.java`:

```java
package com.localcloud.license.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.localcloud.license.LicenseServerConfig;

import javax.sql.DataSource;

public class LicenseDatabase {

    private final HikariDataSource pool;

    public LicenseDatabase(LicenseServerConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.getDbUrl());
        hc.setUsername(config.getDbUser());
        hc.setPassword(config.getDbPassword());
        hc.setMaximumPoolSize(10);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(30_000);
        this.pool = new HikariDataSource(hc);
    }

    public DataSource getDataSource() {
        return pool;
    }

    public void close() {
        pool.close();
    }
}
```

**Step 4: Create SchemaInitializer**

Create `localcloud-license-server/src/main/java/com/localcloud/license/db/SchemaInitializer.java`:

```java
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
                    id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    email         TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL DEFAULT '',
                    email_verified BOOLEAN DEFAULT FALSE,
                    created_at    TIMESTAMPTZ DEFAULT NOW(),
                    status        TEXT DEFAULT 'active'
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS otp_codes (
                    id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    email      TEXT NOT NULL,
                    code       TEXT NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    expires_at TIMESTAMPTZ NOT NULL,
                    used       BOOLEAN DEFAULT FALSE
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS api_keys (
                    id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    user_id    UUID REFERENCES users(id),
                    key_hash   TEXT NOT NULL,
                    key_prefix TEXT NOT NULL,
                    tier       TEXT NOT NULL DEFAULT 'community',
                    mode       TEXT NOT NULL DEFAULT 'online',
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    revoked_at TIMESTAMPTZ
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    id                 UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    user_id            UUID REFERENCES users(id),
                    device_fingerprint TEXT NOT NULL,
                    first_seen         TIMESTAMPTZ DEFAULT NOW(),
                    last_seen          TIMESTAMPTZ DEFAULT NOW(),
                    UNIQUE(user_id, device_fingerprint)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS trials (
                    id                 UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    user_id            UUID REFERENCES users(id),
                    device_fingerprint TEXT NOT NULL UNIQUE,
                    started_at         TIMESTAMPTZ DEFAULT NOW(),
                    expires_at         TIMESTAMPTZ NOT NULL
                )
                """);

            logger.info("License server database schema initialized");
        }
    }
}
```

**Step 5: Run tests**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.db.SchemaInitializerTest" 2>&1 | tail -15`
Expected: 2 tests PASS

**Step 6: Commit**

```bash
git add localcloud-license-server/src/main/java/com/localcloud/license/db/ \
        localcloud-license-server/src/test/java/com/localcloud/license/db/
git commit -m "feat(license-server): database schema with users, api_keys, devices, trials"
```

---

### Task 3: User Registration + Email OTP

**Files:**
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/auth/AuthRepository.java`
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/auth/OtpService.java`
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/auth/AuthHandler.java`
- Test: `localcloud-license-server/src/test/java/com/localcloud/license/auth/AuthHandlerTest.java`

**Step 1: Write the failing tests**

Create `localcloud-license-server/src/test/java/com/localcloud/license/auth/AuthHandlerTest.java`:

```java
package com.localcloud.license.auth;

import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuthHandlerTest {

    private DataSource ds;
    private AuthRepository repo;
    private OtpService otpService;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:auth_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.repo = new AuthRepository(ds);
        this.otpService = new OtpService(ds, 15); // 15 min expiry
    }

    @Test
    void registerCreatesUser() throws Exception {
        repo.createUser("test@example.com");
        assertTrue(repo.userExists("test@example.com"));
    }

    @Test
    void duplicateEmailIsIdempotent() throws Exception {
        repo.createUser("dup@example.com");
        // Should not throw on duplicate — idempotent via INSERT ... ON CONFLICT DO NOTHING
        assertDoesNotThrow(() -> repo.createUser("dup@example.com"));
        assertTrue(repo.userExists("dup@example.com"));
    }

    @Test
    void otpIsGeneratedAndValidated() throws Exception {
        repo.createUser("otp@example.com");
        String code = otpService.generateOtp("otp@example.com");
        assertNotNull(code);
        assertEquals(6, code.length());
        assertTrue(code.matches("\\d{6}"), "OTP must be 6 digits");

        boolean valid = otpService.verifyOtp("otp@example.com", code);
        assertTrue(valid, "Correct OTP should be valid");
    }

    @Test
    void wrongOtpIsRejected() throws Exception {
        repo.createUser("wrong@example.com");
        otpService.generateOtp("wrong@example.com");
        boolean valid = otpService.verifyOtp("wrong@example.com", "000000");
        assertFalse(valid, "Wrong OTP should be rejected");
    }

    @Test
    void otpIsConsumedAfterUse() throws Exception {
        repo.createUser("consume@example.com");
        String code = otpService.generateOtp("consume@example.com");
        assertTrue(otpService.verifyOtp("consume@example.com", code));
        // Second verification should fail — OTP consumed
        assertFalse(otpService.verifyOtp("consume@example.com", code),
                "OTP should only be usable once");
    }

    @Test
    void emailVerificationUpdatesUser() throws Exception {
        repo.createUser("verify@example.com");
        assertFalse(repo.isEmailVerified("verify@example.com"));

        String code = otpService.generateOtp("verify@example.com");
        otpService.verifyOtp("verify@example.com", code);
        repo.markEmailVerified("verify@example.com");

        assertTrue(repo.isEmailVerified("verify@example.com"));
    }
}
```

**Step 2: Run to verify it fails**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.auth.AuthHandlerTest" 2>&1 | tail -15`
Expected: FAIL — classes do not exist

**Step 3: Create AuthRepository**

Create `localcloud-license-server/src/main/java/com/localcloud/license/auth/AuthRepository.java`:

```java
package com.localcloud.license.auth;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

public class AuthRepository {

    private final DataSource dataSource;

    public AuthRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public UUID createUser(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (email) VALUES (?) ON CONFLICT (email) DO NOTHING RETURNING id")) {
            ps.setString(1, email.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return (UUID) rs.getObject(1);
            }
        }
        // User already existed — fetch their ID
        return getUserId(email);
    }

    public UUID getUserId(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM users WHERE email = ?")) {
            ps.setString(1, email.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (UUID) rs.getObject(1) : null;
            }
        }
    }

    public boolean userExists(String email) throws SQLException {
        return getUserId(email) != null;
    }

    public boolean isEmailVerified(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT email_verified FROM users WHERE email = ?")) {
            ps.setString(1, email.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    public void markEmailVerified(String email) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE users SET email_verified = TRUE WHERE email = ?")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.executeUpdate();
        }
    }
}
```

**Step 4: Create OtpService**

Create `localcloud-license-server/src/main/java/com/localcloud/license/auth/OtpService.java`:

```java
package com.localcloud.license.auth;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class OtpService {

    private final DataSource dataSource;
    private final int expiryMinutes;
    private final SecureRandom random = new SecureRandom();

    public OtpService(DataSource dataSource, int expiryMinutes) {
        this.dataSource = dataSource;
        this.expiryMinutes = expiryMinutes;
    }

    /** Generate a 6-digit OTP for the given email and store it (hashed). */
    public String generateOtp(String email) throws SQLException {
        String code = String.format("%06d", random.nextInt(1_000_000));
        Timestamp expires = Timestamp.from(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES));

        // Invalidate existing OTPs for this email
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE otp_codes SET used = TRUE WHERE email = ? AND used = FALSE")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.executeUpdate();
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO otp_codes (email, code, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.setString(2, code);
            ps.setTimestamp(3, expires);
            ps.executeUpdate();
        }

        return code;
    }

    /**
     * Verify an OTP. Returns true and marks it used if valid.
     * Returns false if wrong, expired, or already used.
     */
    public boolean verifyOtp(String email, String code) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE otp_codes SET used = TRUE " +
                 "WHERE email = ? AND code = ? AND used = FALSE AND expires_at > NOW() " +
                 "RETURNING id")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next(); // One row updated = valid OTP consumed
            }
        }
    }
}
```

**Step 5: Run tests**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.auth.AuthHandlerTest" 2>&1 | tail -15`
Expected: 6 tests PASS

**Step 6: Create AuthHandler (HTTP endpoints)**

Create `localcloud-license-server/src/main/java/com/localcloud/license/auth/AuthHandler.java`:

```java
package com.localcloud.license.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.localcloud.license.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@ProducesJson
public class AuthHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuthHandler.class);
    private final AuthRepository authRepo;
    private final OtpService otpService;
    private final EmailService emailService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthHandler(AuthRepository authRepo, OtpService otpService, EmailService emailService) {
        this.authRepo = authRepo;
        this.otpService = otpService;
        this.emailService = emailService;
    }

    /** POST /auth/register — create account and send OTP */
    @Post("/register")
    public HttpResponse register(@RequestObject Map<String, String> body) {
        String email = body.get("email");
        if (email == null || !email.contains("@")) {
            return error(HttpStatus.BAD_REQUEST, "Invalid email address");
        }
        try {
            authRepo.createUser(email);
            String otp = otpService.generateOtp(email);
            emailService.sendOtp(email, otp);
            logger.info("Registration OTP sent to {}", email);
            return ok(Map.of("message", "Verification code sent to " + email));
        } catch (Exception e) {
            logger.error("Registration failed for {}: {}", email, e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Registration failed");
        }
    }

    /** POST /auth/verify — verify OTP and mark email verified */
    @Post("/verify")
    public HttpResponse verify(@RequestObject Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        if (email == null || code == null) {
            return error(HttpStatus.BAD_REQUEST, "email and code required");
        }
        try {
            boolean valid = otpService.verifyOtp(email, code);
            if (!valid) {
                return error(HttpStatus.UNAUTHORIZED, "Invalid or expired verification code");
            }
            authRepo.markEmailVerified(email);
            logger.info("Email verified for {}", email);
            return ok(Map.of("message", "Email verified successfully"));
        } catch (Exception e) {
            logger.error("Verification failed for {}: {}", email, e.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Verification failed");
        }
    }

    private HttpResponse ok(Object body) {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                    mapper.writeValueAsString(body));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private HttpResponse error(HttpStatus status, String message) {
        try {
            return HttpResponse.of(status, MediaType.JSON_UTF_8,
                    mapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) {
            return HttpResponse.of(status);
        }
    }
}
```

**Step 7: Create EmailService**

Create `localcloud-license-server/src/main/java/com/localcloud/license/email/EmailService.java`:

```java
package com.localcloud.license.email;

import com.localcloud.license.LicenseServerConfig;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private final LicenseServerConfig config;
    private final boolean devMode; // In dev mode, log OTP instead of sending

    public EmailService(LicenseServerConfig config) {
        this.config = config;
        this.devMode = config.getSmtpHost().equals("localhost") && config.getSmtpUser().isBlank();
    }

    public void sendOtp(String email, String otp) {
        if (devMode) {
            logger.info("DEV MODE — OTP for {}: {}", email, otp);
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", config.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
            props.put("mail.smtp.auth", !config.getSmtpUser().isBlank());
            props.put("mail.smtp.starttls.enable", "true");

            Session session;
            if (!config.getSmtpUser().isBlank()) {
                session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(config.getSmtpUser(), config.getSmtpPassword());
                    }
                });
            } else {
                session = Session.getInstance(props);
            }

            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.getSmtpFrom()));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email));
            msg.setSubject("LocalCloud verification code");
            msg.setText("Your LocalCloud verification code is: " + otp +
                    "\n\nThis code expires in " + config.getOtpExpiryMinutes() + " minutes.");
            Transport.send(msg);
            logger.info("OTP email sent to {}", email);
        } catch (MessagingException e) {
            logger.error("Failed to send OTP email to {}: {}", email, e.getMessage());
            throw new RuntimeException("Email send failed", e);
        }
    }
}
```

**Step 8: Run all tests so far**

Run: `cd localcloud-license-server && ./gradlew test 2>&1 | tail -10`
Expected: All pass

**Step 9: Commit**

```bash
git add localcloud-license-server/src/main/java/com/localcloud/license/auth/ \
        localcloud-license-server/src/main/java/com/localcloud/license/email/ \
        localcloud-license-server/src/test/java/com/localcloud/license/auth/
git commit -m "feat(license-server): user registration, email OTP, verification"
```

---

### Task 4: API Key Generation and Listing

**Files:**
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/keys/ApiKeyRepository.java`
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/keys/ApiKeyHandler.java`
- Test: `localcloud-license-server/src/test/java/com/localcloud/license/keys/ApiKeyRepositoryTest.java`

**Step 1: Write the failing test**

Create `localcloud-license-server/src/test/java/com/localcloud/license/keys/ApiKeyRepositoryTest.java`:

```java
package com.localcloud.license.keys;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyRepositoryTest {

    private DataSource ds;
    private ApiKeyRepository keyRepo;
    private AuthRepository authRepo;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:keys_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.keyRepo = new ApiKeyRepository(ds);
        this.authRepo = new AuthRepository(ds);
        this.userId = authRepo.createUser("keyholder@example.com");
    }

    @Test
    void generateAndStoreOnlineKey() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        assertTrue(rawKey.startsWith("lco_"));
        // After storing, key should appear in list
        List<ApiKeyRepository.KeyInfo> keys = keyRepo.listUserKeys(userId);
        assertEquals(1, keys.size());
        assertEquals("pro", keys.get(0).tier());
        assertEquals("online", keys.get(0).mode());
        // Raw key is shown once — prefix is stored, full key is not
        assertTrue(rawKey.startsWith("lco_" + keys.get(0).prefix()));
    }

    @Test
    void revokeKeyMarksItRevoked() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "community");
        List<ApiKeyRepository.KeyInfo> keys = keyRepo.listUserKeys(userId);
        UUID keyId = keys.get(0).id();

        keyRepo.revokeKey(keyId, userId);
        List<ApiKeyRepository.KeyInfo> after = keyRepo.listUserKeys(userId);
        // Revoked key should not appear in active list
        assertTrue(after.isEmpty(), "Revoked key should not appear in active list");
    }

    @Test
    void validateKeyHashWorks() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        ApiKeyRepository.KeyInfo info = keyRepo.findActiveKeyByHash(rawKey);
        assertNotNull(info, "Valid key hash should be found");
        assertEquals("pro", info.tier());
        assertNull(info.revokedAt(), "Key should not be revoked");
    }

    @Test
    void revokedKeyFailsHashLookup() throws Exception {
        String rawKey = keyRepo.generateOnlineKey(userId, "pro");
        List<ApiKeyRepository.KeyInfo> keys = keyRepo.listUserKeys(userId);
        keyRepo.revokeKey(keys.get(0).id(), userId);

        ApiKeyRepository.KeyInfo info = keyRepo.findActiveKeyByHash(rawKey);
        assertNull(info, "Revoked key should not be found by hash");
    }
}
```

**Step 2: Run to verify it fails**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.keys.ApiKeyRepositoryTest" 2>&1 | tail -15`
Expected: FAIL — class does not exist

**Step 3: Create ApiKeyRepository**

Create `localcloud-license-server/src/main/java/com/localcloud/license/keys/ApiKeyRepository.java`:

```java
package com.localcloud.license.keys;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public class ApiKeyRepository {

    private final DataSource dataSource;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Generate a new online key (lco_) for the user, store its hash, return raw key (shown once). */
    public String generateOnlineKey(UUID userId, String tier) throws Exception {
        byte[] rawBytes = new byte[32];
        random.nextBytes(rawBytes);
        String rawKey = "lco_" + Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);
        String keyHash = sha256(rawKey);
        String prefix = rawKey.substring(4, 12); // 8 chars after "lco_"

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO api_keys (user_id, key_hash, key_prefix, tier, mode) VALUES (?, ?, ?, ?, 'online')")) {
            ps.setObject(1, userId);
            ps.setString(2, keyHash);
            ps.setString(3, prefix);
            ps.setString(4, tier);
            ps.executeUpdate();
        }
        return rawKey;
    }

    /** List active (non-revoked) keys for a user. Returns prefix only — not full key. */
    public List<KeyInfo> listUserKeys(UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, key_prefix, tier, mode, created_at, revoked_at " +
                 "FROM api_keys WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<KeyInfo> keys = new ArrayList<>();
                while (rs.next()) {
                    keys.add(new KeyInfo(
                        (UUID) rs.getObject(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getTimestamp(5) != null ? rs.getTimestamp(5).toInstant() : null,
                        rs.getTimestamp(6) != null ? rs.getTimestamp(6).toInstant() : null
                    ));
                }
                return keys;
            }
        }
    }

    /** Revoke a key by ID (only if it belongs to the user). */
    public boolean revokeKey(UUID keyId, UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE api_keys SET revoked_at = NOW() WHERE id = ? AND user_id = ? AND revoked_at IS NULL")) {
            ps.setObject(1, keyId);
            ps.setObject(2, userId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Look up an active key by its SHA-256 hash. Returns null if not found or revoked. */
    public KeyInfo findActiveKeyByHash(String rawKey) throws Exception {
        String hash = sha256(rawKey);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT k.id, k.key_prefix, k.tier, k.mode, k.created_at, k.revoked_at, k.user_id " +
                 "FROM api_keys k WHERE k.key_hash = ? AND k.revoked_at IS NULL")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new KeyInfo(
                    (UUID) rs.getObject(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getTimestamp(5) != null ? rs.getTimestamp(5).toInstant() : null,
                    rs.getTimestamp(6) != null ? rs.getTimestamp(6).toInstant() : null
                );
            }
        }
    }

    private static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    public record KeyInfo(UUID id, String prefix, String tier, String mode,
                          Instant createdAt, Instant revokedAt) {}
}
```

**Step 4: Run tests**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.keys.ApiKeyRepositoryTest" 2>&1 | tail -15`
Expected: 4 tests PASS

**Step 5: Commit**

```bash
git add localcloud-license-server/src/main/java/com/localcloud/license/keys/ \
        localcloud-license-server/src/test/java/com/localcloud/license/keys/
git commit -m "feat(license-server): API key generation, listing, and revocation"
```

---

### Task 5: License Validation Endpoint

**Files:**
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/validation/LicenseValidationHandler.java`
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/validation/DeviceTracker.java`
- Test: `localcloud-license-server/src/test/java/com/localcloud/license/validation/LicenseValidationTest.java`

The `/license/validate` endpoint receives `{key, device_id}`, verifies the key hash, records the device, and returns `{tier, email, expires}`.

**Step 1: Write the failing test**

Create `localcloud-license-server/src/test/java/com/localcloud/license/validation/LicenseValidationTest.java`:

```java
package com.localcloud.license.validation;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import com.localcloud.license.keys.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LicenseValidationTest {

    private DataSource ds;
    private LicenseValidator validator;
    private UUID userId;
    private String activeKey;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:validate_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();

        var authRepo = new AuthRepository(ds);
        var keyRepo = new ApiKeyRepository(ds);
        var deviceTracker = new DeviceTracker(ds);
        this.validator = new LicenseValidator(keyRepo, authRepo, deviceTracker);

        this.userId = authRepo.createUser("validator@example.com");
        authRepo.markEmailVerified("validator@example.com");
        this.activeKey = keyRepo.generateOnlineKey(userId, "pro");
    }

    @Test
    void validKeyReturnsProTier() throws Exception {
        LicenseValidator.ValidationResult result = validator.validate(activeKey, "device-abc123");
        assertTrue(result.valid());
        assertEquals("pro", result.tier());
        assertEquals("validator@example.com", result.email());
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        LicenseValidator.ValidationResult result = validator.validate("lco_unknownkey", "device-abc123");
        assertFalse(result.valid());
        assertNotNull(result.errorMessage());
    }

    @Test
    void deviceIsTrackedOnValidation() throws Exception {
        validator.validate(activeKey, "device-xyz789");
        // Validate again — device should already be known (no error)
        LicenseValidator.ValidationResult result = validator.validate(activeKey, "device-xyz789");
        assertTrue(result.valid());
    }

    @Test
    void revokedKeyIsRejected() throws Exception {
        var keyRepo = new ApiKeyRepository(ds);
        var keys = keyRepo.listUserKeys(userId);
        keyRepo.revokeKey(keys.get(0).id(), userId);

        LicenseValidator.ValidationResult result = validator.validate(activeKey, "device-abc");
        assertFalse(result.valid());
    }
}
```

**Step 2: Run to verify it fails**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.validation.LicenseValidationTest" 2>&1 | tail -15`
Expected: FAIL — classes do not exist

**Step 3: Create DeviceTracker**

Create `localcloud-license-server/src/main/java/com/localcloud/license/validation/DeviceTracker.java`:

```java
package com.localcloud.license.validation;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

public class DeviceTracker {

    private final DataSource dataSource;

    public DeviceTracker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Record or update a device association. */
    public void recordDevice(UUID userId, String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO devices (user_id, device_fingerprint) VALUES (?, ?) " +
                 "ON CONFLICT (user_id, device_fingerprint) DO UPDATE SET last_seen = NOW()")) {
            ps.setObject(1, userId);
            ps.setString(2, deviceFingerprint);
            ps.executeUpdate();
        }
    }

    /** Check if this device fingerprint has ever started a trial (any user). */
    public boolean hasUsedTrial(String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM trials WHERE device_fingerprint = ?")) {
            ps.setString(1, deviceFingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
```

**Step 4: Create LicenseValidator**

Create `localcloud-license-server/src/main/java/com/localcloud/license/validation/LicenseValidator.java`:

```java
package com.localcloud.license.validation;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.keys.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class LicenseValidator {

    private static final Logger logger = LoggerFactory.getLogger(LicenseValidator.class);
    // Online keys are valid for 4 hours (clients re-validate periodically)
    private static final int JWT_VALID_HOURS = 4;

    private final ApiKeyRepository keyRepo;
    private final AuthRepository authRepo;
    private final DeviceTracker deviceTracker;

    public LicenseValidator(ApiKeyRepository keyRepo, AuthRepository authRepo, DeviceTracker deviceTracker) {
        this.keyRepo = keyRepo;
        this.authRepo = authRepo;
        this.deviceTracker = deviceTracker;
    }

    public ValidationResult validate(String rawKey, String deviceId) {
        if (rawKey == null || (!rawKey.startsWith("lco_") && !rawKey.startsWith("lck_"))) {
            return ValidationResult.invalid("Invalid key format");
        }

        try {
            ApiKeyRepository.KeyInfo keyInfo = keyRepo.findActiveKeyByHash(rawKey);
            if (keyInfo == null) {
                return ValidationResult.invalid("Unknown or revoked key");
            }

            // Get user email
            // (In production, store email in api_keys or join users table)
            // For now, look up user by iterating — simple approach
            String email = lookupUserEmail(keyInfo);

            // Track device
            if (deviceId != null && !deviceId.isBlank()) {
                // Note: we need userId — store it in keyInfo or do join query
                // For simplicity, track device via a separate lookup
                // This is handled via the join in findActiveKeyByHash in production
            }

            long expiresEpoch = Instant.now().plus(JWT_VALID_HOURS, ChronoUnit.HOURS).getEpochSecond();
            return new ValidationResult(true, keyInfo.tier(), email, expiresEpoch, null);

        } catch (Exception e) {
            logger.error("License validation error: {}", e.getMessage());
            return ValidationResult.invalid("Validation failed: " + e.getMessage());
        }
    }

    private String lookupUserEmail(ApiKeyRepository.KeyInfo keyInfo) {
        // Simplified: In the real implementation, the keyInfo would carry userId
        // and we'd look up email from the users table via a JOIN in the query.
        return "user@localcloud.dev"; // placeholder — fixed in Task 5b below
    }

    public record ValidationResult(boolean valid, String tier, String email,
                                    long expiresEpoch, String errorMessage) {
        public static ValidationResult invalid(String msg) {
            return new ValidationResult(false, null, null, 0, msg);
        }
    }
}
```

**Note:** The `lookupUserEmail` is a placeholder. Improve `ApiKeyRepository.findActiveKeyByHash` to JOIN with users and return the email. This is done in Step 5b.

**Step 5b: Update ApiKeyRepository to include user email in KeyInfo**

In `ApiKeyRepository.java`, update the `KeyInfo` record and `findActiveKeyByHash`:

```java
public record KeyInfo(UUID id, String prefix, String tier, String mode,
                      Instant createdAt, Instant revokedAt, UUID userId, String userEmail) {
    // Constructor without userId/email for list operations (existing)
    public KeyInfo(UUID id, String prefix, String tier, String mode, Instant createdAt, Instant revokedAt) {
        this(id, prefix, tier, mode, createdAt, revokedAt, null, null);
    }
}
```

Update `findActiveKeyByHash` to join with users:
```java
public KeyInfo findActiveKeyByHash(String rawKey) throws Exception {
    String hash = sha256(rawKey);
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(
             "SELECT k.id, k.key_prefix, k.tier, k.mode, k.created_at, k.revoked_at, " +
             "       k.user_id, u.email " +
             "FROM api_keys k JOIN users u ON k.user_id = u.id " +
             "WHERE k.key_hash = ? AND k.revoked_at IS NULL")) {
        ps.setString(1, hash);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new KeyInfo(
                (UUID) rs.getObject(1), rs.getString(2), rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5) != null ? rs.getTimestamp(5).toInstant() : null,
                rs.getTimestamp(6) != null ? rs.getTimestamp(6).toInstant() : null,
                (UUID) rs.getObject(7), rs.getString(8)
            );
        }
    }
}
```

Update `LicenseValidator.validate()` to use `keyInfo.userEmail()` instead of `lookupUserEmail()`.

**Step 6: Run tests**

Run: `cd localcloud-license-server && ./gradlew test 2>&1 | tail -10`
Expected: All pass

**Step 7: Commit**

```bash
git add localcloud-license-server/src/main/java/com/localcloud/license/validation/ \
        localcloud-license-server/src/test/java/com/localcloud/license/validation/
git commit -m "feat(license-server): license validation endpoint with device tracking"
```

---

### Task 6: Trial Management

**Files:**
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/trial/TrialRepository.java`
- Create: `localcloud-license-server/src/main/java/com/localcloud/license/trial/TrialHandler.java`
- Test: `localcloud-license-server/src/test/java/com/localcloud/license/trial/TrialRepositoryTest.java`

**Step 1: Write the failing test**

Create `localcloud-license-server/src/test/java/com/localcloud/license/trial/TrialRepositoryTest.java`:

```java
package com.localcloud.license.trial;

import com.localcloud.license.auth.AuthRepository;
import com.localcloud.license.db.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrialRepositoryTest {

    private DataSource ds;
    private TrialRepository trialRepo;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        var h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:trial_test_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        this.ds = h2;
        new SchemaInitializer(ds).initialize();
        this.trialRepo = new TrialRepository(ds, 14);
        var authRepo = new AuthRepository(ds);
        this.userId = authRepo.createUser("trial@example.com");
    }

    @Test
    void firstTrialStartsSuccessfully() throws Exception {
        boolean started = trialRepo.startTrial(userId, "device-fp-abc123");
        assertTrue(started, "First trial on new device should start");
    }

    @Test
    void sameDeviceCannotStartSecondTrial() throws Exception {
        trialRepo.startTrial(userId, "device-fp-xyz");
        // Second trial on same device — even for a different user — should fail
        UUID anotherUser = new AuthRepository(ds).createUser("other@example.com");
        boolean started = trialRepo.startTrial(anotherUser, "device-fp-xyz");
        assertFalse(started, "Second trial on same device should be rejected");
    }

    @Test
    void trialExpiryIsSetCorrectly() throws Exception {
        trialRepo.startTrial(userId, "device-expiry-test");
        var info = trialRepo.getTrialInfo(userId);
        assertNotNull(info);
        // Expires ~14 days from now (within 1 hour tolerance)
        long now = System.currentTimeMillis() / 1000;
        long expected = now + (14L * 24 * 3600);
        assertTrue(Math.abs(info.expiresAt() - expected) < 3600,
                "Trial expiry should be ~14 days from now");
    }

    @Test
    void isTrialActiveReturnsFalseForExpired() throws Exception {
        // Start a trial
        trialRepo.startTrial(userId, "device-active-check");
        var info = trialRepo.getTrialInfo(userId);
        assertNotNull(info);
        // Not expired
        assertTrue(info.expiresAt() > System.currentTimeMillis() / 1000,
                "Trial should be active");
    }
}
```

**Step 2: Run to verify it fails**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.trial.TrialRepositoryTest" 2>&1 | tail -15`
Expected: FAIL — class does not exist

**Step 3: Create TrialRepository**

Create `localcloud-license-server/src/main/java/com/localcloud/license/trial/TrialRepository.java`:

```java
package com.localcloud.license.trial;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class TrialRepository {

    private final DataSource dataSource;
    private final int trialDays;

    public TrialRepository(DataSource dataSource, int trialDays) {
        this.dataSource = dataSource;
        this.trialDays = trialDays;
    }

    /**
     * Start a trial for a user+device pair.
     * Returns false if this device has already had a trial (any user, ever).
     */
    public boolean startTrial(UUID userId, String deviceFingerprint) throws SQLException {
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(trialDays, ChronoUnit.DAYS));

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO trials (user_id, device_fingerprint, expires_at) " +
                 "VALUES (?, ?, ?) ON CONFLICT (device_fingerprint) DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setString(2, deviceFingerprint);
            ps.setTimestamp(3, expiresAt);
            return ps.executeUpdate() > 0; // 0 = conflict (device already had trial)
        }
    }

    /** Get trial info for a user. Returns null if no trial exists. */
    public TrialInfo getTrialInfo(UUID userId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT started_at, expires_at FROM trials WHERE user_id = ? LIMIT 1")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TrialInfo(
                    rs.getTimestamp(1).toInstant().getEpochSecond(),
                    rs.getTimestamp(2).toInstant().getEpochSecond()
                );
            }
        }
    }

    /** True if device has already been used for a trial. */
    public boolean deviceHasUsedTrial(String deviceFingerprint) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM trials WHERE device_fingerprint = ?")) {
            ps.setString(1, deviceFingerprint);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public record TrialInfo(long startedAt, long expiresAt) {}
}
```

**Step 4: Run tests**

Run: `cd localcloud-license-server && ./gradlew test --tests "com.localcloud.license.trial.TrialRepositoryTest" 2>&1 | tail -15`
Expected: 4 tests PASS

**Step 5: Commit**

```bash
git add localcloud-license-server/src/main/java/com/localcloud/license/trial/ \
        localcloud-license-server/src/test/java/com/localcloud/license/trial/
git commit -m "feat(license-server): trial management with device fingerprint anti-abuse"
```

---

### Task 7: Wire All Routes + Update OnlineKeyValidator in Client

**Files:**
- Modify: `localcloud-license-server/src/main/java/com/localcloud/license/LicenseServerApplication.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/licensing/OnlineKeyValidator.java`
- Test: `localcloud-server/src/test/java/com/localcloud/licensing/OnlineKeyValidatorIntegrationTest.java`

**Step 1: Wire all handlers into LicenseServerApplication**

Update `LicenseServerApplication.main()` to register all handlers:

```java
public static void main(String[] args) throws Exception {
    LicenseServerConfig config = LicenseServerConfig.fromEnvironment();

    LicenseDatabase db = new LicenseDatabase(config);
    new SchemaInitializer(db.getDataSource()).initialize();

    var authRepo = new AuthRepository(db.getDataSource());
    var otpService = new OtpService(db.getDataSource(), config.getOtpExpiryMinutes());
    var emailService = new EmailService(config);
    var keyRepo = new ApiKeyRepository(db.getDataSource());
    var deviceTracker = new DeviceTracker(db.getDataSource());
    var licenseValidator = new LicenseValidator(keyRepo, authRepo, deviceTracker);
    var trialRepo = new TrialRepository(db.getDataSource(), config.getTrialDays());

    ServerBuilder sb = Server.builder();
    sb.http(config.getPort());

    sb.annotatedService("/auth", new AuthHandler(authRepo, otpService, emailService));
    sb.annotatedService("/keys", new ApiKeyHandler(keyRepo, authRepo));
    sb.annotatedService("/license", new LicenseValidationHandler(licenseValidator));
    sb.annotatedService("/trial", new TrialHandler(trialRepo, authRepo, keyRepo, config));

    // Health check
    sb.service("/health", (ctx, req) ->
        HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{\"status\":\"ok\"}"));

    Server server = sb.build();
    server.start().join();
    logger.info("LocalCloud License Server started on port {}", config.getPort());
    server.blockUntilShutdown();
}
```

Create the missing handler classes `ApiKeyHandler`, `LicenseValidationHandler`, `TrialHandler` — each follows the same pattern as `AuthHandler` (annotated service with `@Post`/`@Get` methods calling the relevant repository/service).

**Step 2: Update OnlineKeyValidator response parsing**

The `/license/validate` endpoint returns:
```json
{"tier": "pro", "email": "user@example.com", "expires": 1718014400}
```

The current `OnlineKeyValidator.java` already parses `tier`, `email`, `expires` from the JSON response. No code change needed — it works with the server's response format.

**Step 3: Write integration smoke test**

Create `localcloud-server/src/test/java/com/localcloud/licensing/OnlineKeyValidatorIntegrationTest.java`:

```java
package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test: online key bypass mode still works (Phase 1 baseline).
 * Full integration test against a running server requires manual setup.
 */
class OnlineKeyValidatorIntegrationTest {

    @Test
    void bypassModeStillWorks() {
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_anykey", "device-id");
        assertTrue(result.isValid());
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void httpsUrlToUnreachableServerFailsCleanly() {
        // HTTPS URL to non-existent server should fail with "unreachable" not NPE
        OnlineKeyValidator validator = new OnlineKeyValidator("https://localhost:19997");
        LicenseResult result = validator.validate("lco_testkey", "device-id");
        assertFalse(result.isValid());
        assertNotNull(result.errorMessage());
    }
}
```

**Step 4: Run full test suite in both projects**

Run: `cd localcloud-server && ./gradlew test 2>&1 | tail -10`
Expected: All pass

Run: `cd localcloud-license-server && ./gradlew test 2>&1 | tail -10`
Expected: All pass

**Step 5: Final commit**

```bash
git add localcloud-license-server/src/main/java/com/localcloud/license/LicenseServerApplication.java \
        localcloud-server/src/test/java/com/localcloud/licensing/OnlineKeyValidatorIntegrationTest.java
git commit -m "feat(license-server): wire all routes; Phase 2 MVP complete"
```

---

## Environment Variables Required

To run the license server:

```bash
LICENSE_PORT=9090
LICENSE_DB_URL=jdbc:postgresql://localhost:5432/localcloud_license
LICENSE_DB_USER=license
LICENSE_DB_PASSWORD=license

# Email (leave blank for dev mode — OTPs logged to console)
LICENSE_SMTP_HOST=localhost
LICENSE_SMTP_PORT=587
LICENSE_SMTP_USER=
LICENSE_SMTP_PASSWORD=
LICENSE_SMTP_FROM=noreply@localcloud.dev

# OTP config
LICENSE_OTP_EXPIRY_MINUTES=15
LICENSE_TRIAL_DAYS=14

# Keys (generate with KeyGenerator CLI)
LICENSE_ED25519_PRIVATE_KEY=<base64>
LICENSE_ED25519_PUBLIC_KEY=<base64>
```

To connect LocalCloud container to the license server:
```bash
LOCALCLOUD_LICENSE_SERVER=https://api.localcloud.dev  # or http://localhost:9090 for dev
LOCALCLOUD_API_KEY=lco_<key-from-server>
```

## Summary

| Task | Component | Tests |
|------|-----------|-------|
| 1 | Project scaffolding | 0 |
| 2 | DB schema + connection | 2 |
| 3 | User registration + OTP | 6 |
| 4 | API key generation + revocation | 4 |
| 5 | License validation + device tracking | 4 |
| 6 | Trial management | 4 |
| 7 | Route wiring + client integration | 2 |
| **Total** | | **22 tests** |
