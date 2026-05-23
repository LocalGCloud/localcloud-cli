# License Server — Architecture & Implementation

**Version:** 1.0
**Date:** 2026-05-21

---

## 1. System Context

```
┌─────────────────────────────────────────────────────┐
│                  Docker Container                     │
│                                                       │
│  ┌───────────────────┐     ┌──────────────────────┐  │
│  │  localcloud-server │────▶│  localcloud-license  │  │
│  │  (Armeria :8080)   │     │  -server (Armeria    │  │
│  │                    │◀────│  :9090)              │  │
│  │  OnlineKeyValidator│     │                      │  │
│  │  LicenseManager    │     │  /license/validate   │  │
│  │  LicenseGateMain   │     │  /keys/generate      │  │
│  └───────────────────┘     │  /auth/*              │  │
│                            │  /trial/*             │  │
│  ┌───────────────────┐     │  /admin/*             │  │
│  │  PostgreSQL :5432  │◀────│                      │  │
│  │  (shared schema)   │     └──────────────────────┘  │
│  └───────────────────┘                                │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │  Supervisord (manages emulator processes)     │    │
│  │  GCS :4443 │ PubSub :8085 │ Firestore :8086  │    │
│  │  Bigtable :8087 │ Spanner :9010 │ BQ :9050   │    │
│  └──────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

The license server is a **co-located microservice** — it runs in the same Docker container as the gateway and emulators, but as a separate JVM process with its own port (9090). It can also be deployed standalone for the hosted `api.localcloud.dev` service.

The gateway (`localcloud-server`) acts as the client during startup: `LicenseManager` in the gateway calls the license server's `/license/validate` endpoint to obtain a signed JWT before enabling services.

---

## 2. Module Structure

```
localcloud-license-server/
├── build.gradle
└── src/
    ├── main/java/com/localcloud/license/
    │   ├── LicenseServerApplication.java      ─── Entry point (Armeria server)
    │   ├── LicenseServerConfig.java           ─── DI wiring, config loading
    │   │
    │   ├── admin/                             ─── Admin REST endpoints & console
    │   │   ├── AdminHandler.java              ─── Routes: /admin/login, /admin/stats, /admin/users
    │   │   ├── AdminSessionDecorator.java     ─── Armeria decorator for admin auth
    │   │   ├── AdminSessionStore.java         ─── In-memory admin session store
    │   │   └── AdminStatsRepository.java      ─── Usage metrics persistence (PostgreSQL, UPSERT)
    │   │
    │   ├── auth/                              ─── User auth & sessions
    │   │   ├── AuthHandler.java               ─── /auth/register, /auth/verify, /auth/login
    │   │   ├── AuthRepository.java            ─── User CRUD + email verification
    │   │   ├── OtpService.java                ─── OTP generation + email sending
    │   │   ├── SessionRepository.java         ─── Session token lifecycle
    │   │   └── SessionAuthDecorator.java      ─── Bearer token decorator for user endpoints
    │   │
    │   ├── db/                                ─── Database connections & schema
    │   │   ├── LicenseDatabase.java           ─── HikariCP DataSource management
    │   │   └── SchemaInitializer.java         ─── Schema migration on startup
    │   │
    │   ├── email/                             ─── Email delivery
    │   │   └── EmailService.java              ─── JavaMail SMTP sender
    │   │
    │   ├── keys/                              ─── API key operations
    │   │   ├── AdminCliKeyGen.java            ─── CLI tool for key pair generation
    │   │   ├── ApiKeyHandler.java             ─── /keys/generate, /keys/list, /keys/revoke
    │   │   ├── ApiKeyRepository.java          ─── Key persistence (hash, prefix, tier)
    │   │   └── KeyPairRepository.java         ─── RSA key pair persistence
    │   │
    │   ├── trial/                             ─── Trial management
    │   │   ├── TrialHandler.java              ─── /trial/start
    │   │   └── TrialRepository.java           ─── Trial persistence + device dedup
    │   │
    │   └── validation/                        ─── License validation & signing
    │       ├── DeviceTracker.java             ─── Device fingerprint lookup/registration
    │       ├── JwtSigner.java                 ─── RS256 JWT signing (JJWT 0.12.x)
    │       ├── KeyPairManager.java            ─── RSA-2048 key pair loading/generation
    │       ├── LicenseValidationHandler.java  ─── /license/validate → signed JWT
    │       └── LicenseValidator.java          ─── Core validation logic
    │
    ├── main/resources/
    │   ├── schema.sql                         ─── Full PostgreSQL DDL
    │   ├── app.conf                           ─── Application config
    │   ├── logback.xml                        ─── Logging config
    │   └── admin/                             ─── Solid.js admin SPA built artifacts
    │
    └── test/java/com/localcloud/license/      ─── 89 unit tests (JUnit 5 + Mockito)
```

---

## 3. Data Model

### 3.1 Entity Relationship

```
users ──────< api_keys          : one user → many keys
users ──────< sessions          : one user → many sessions
users ──────< trials            : one user → many trials (device-bound)
users ──────< subscriptions     : one user → many subscriptions
key_pairs                       : standalone RSA key pair storage
admin_sessions                  : in-memory, not persisted
```

### 3.2 Core Tables (from `schema.sql`)

**`users`**
```sql
id            UUID PK DEFAULT gen_random_uuid()
email         TEXT UNIQUE NOT NULL
password_hash TEXT NOT NULL          -- for future password auth
email_verified BOOLEAN DEFAULT FALSE
status        TEXT DEFAULT 'active'
created_at    TIMESTAMPTZ DEFAULT now()
```

**`api_keys`**
```sql
id            UUID PK DEFAULT gen_random_uuid()
user_id       UUID REFERENCES users(id)
key_hash      TEXT NOT NULL           -- SHA-256 of raw key
key_prefix    TEXT NOT NULL           -- first 8 chars for identification
tier          TEXT NOT NULL           -- trial, community, pro, team, enterprise
mode          TEXT DEFAULT 'online'   -- online or offline
expires_at    TIMESTAMPTZ
revoked_at    TIMESTAMPTZ
created_at    TIMESTAMPTZ DEFAULT now()
```

**`sessions`**
```sql
token         VARCHAR(512) PK
user_id       UUID REFERENCES users(id)
created_at    TIMESTAMPTZ DEFAULT now()
expires_at    TIMESTAMPTZ NOT NULL
```

**`trials`**
```sql
id                 UUID PK DEFAULT gen_random_uuid()
user_id            UUID REFERENCES users(id)
device_fingerprint TEXT NOT NULL
started_at         TIMESTAMPTZ DEFAULT now()
expires_at         TIMESTAMPTZ NOT NULL
```

### 3.3 Key Design Decisions

| Decision | Rationale |
|---|---|
| Keys stored hashed (SHA-256) | Raw key shown once at creation; compromise of DB does not leak keys |
| Key prefix stored for identification | UI shows `lco_a1b2c3d4...` without full key; user identifies keys by prefix |
| `expires_at` nullable on api_keys | `NULL` = perpetual key (community, enterprise); non-NULL = bounded (trial, subscription) |
| Sessions use VARCHAR(512) PK | PK on token enables fast lookup; no user has many sessions |
| No foreign key cascades | Application-level referential integrity; prevent accidental mass delete |
| `interval` cleanup for sessions | Periodic `DELETE FROM sessions WHERE expires_at < now()` on every 100th operation |

---

## 4. API Surface

### 4.1 Public Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/register` | None | Register with email |
| POST | `/auth/verify` | None | Verify email with OTP |
| POST | `/auth/login` | None | Login → session token |
| POST | `/keys/generate` | Session | Create API key (requires verified email) |
| GET | `/keys/list` | Session | List user's keys (prefix only) |
| POST | `/keys/revoke` | Session | Revoke a specific key |
| POST | `/license/validate` | None | Validate key + device → signed JWT |
| POST | `/trial/start` | None | Start 14-day trial |
| GET | `/license/public-key` | None | Get license server's public key (for client bootstrapping) |

### 4.2 Admin Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/admin/login` | None | Admin login → session |
| POST | `/admin/logout` | Admin session | End admin session |
| GET | `/admin/stats` | Admin session | Request counts, user counts |
| GET | `/admin/users` | Admin session | User listing |
| GET | `/admin/users/{id}` | Admin session | User detail |
| GET | `/admin/keys` | Admin session | Key listing (all users) |
| POST | `/admin/keys` | Admin session | Generate key for any tier |
| POST | `/admin/keys/revoke` | Admin session | Revoke any key |
| GET | `/admin/trials` | Admin session | Trial status overview |

### 4.3 Endpoint Response Patterns

**Success:**
```json
{ "token": "<JWT>" }
{ "key": "lco_...", "tier": "pro", "email": "user@example.com" }
{ "message": "Key revoked" }
{ "session_token": "adm_...", "expires_in_seconds": 86400 }
```

**Error:**
```json
{ "error": true, "message": "Trial expired. Visit https://localcloud.dev/pricing to upgrade." }
{ "error": true, "message": "Invalid session token" }
{ "error": true, "message": "Email already registered" }
```

---

## 5. Key Design Decisions

### 5.1 Key Material

- **Online keys** (`lco_`): Random 32 bytes, base64url-encoded, prefixed. Stored as SHA-256 hash.
- **Offline keys** (`lck_`): Ed25519 key pair. Private key on license server; public key embedded in gateway JAR.
- **JWT signing**: RS256 (RSA-2048 JWK). Private key loaded from env `LOCALCLOUD_LICENSE_PRIVATE_KEY` or ephemeral at startup. Ephemeral mode logs a warning — clients must fetch public key from `/license/public-key` on every restart.
- **Cache protection**: HMAC-SHA256 using a derived key (embedded secret XOR'd with device fingerprint). Protects the cached JWT from tampering on disk.

### 5.2 Validation Pipeline

```
/license/validate
  → Parse api_key from body
  → Look up key_hash in api_keys table
  → If not found → 404 "Invalid license key"
  → If revoked_at IS NOT NULL → 401 "Key revoked"
  → If expires_at < now() → 401 "License expired"
  → If tier == "trial" → check trials table for expiry
  → Record/update device → devices table (upsert)
  → Generate RS256 JWT with tier, email, device_id, iss, iat, exp
  → Return { "token": "<JWT>" }
```

### 5.3 Authentication Layers

Two separate auth mechanisms, each with its own decorator:

| Mechanism | Used For | Implementation |
|---|---|---|
| **Session tokens** | User endpoints (`/keys/*`) | `SessionRepository` + `SessionAuthDecorator` |
| **Admin sessions** | Admin endpoints | `AdminSessionStore` (in-memory) + `AdminSessionDecorator` |

Session tokens expire after a configurable period (default 24h). Admin sessions expire after 24h with inactivity timeout of 1h.

### 5.4 Trial Deduplication

Trials are bound to device fingerprints, not just emails. This prevents:
- Same machine, new email → blocked (same device fingerprint)
- Same email, new machine → allowed (new device fingerprint)
- Delete and recreate container → same fingerprint → blocked

Device fingerprint is computed client-side from hardware signals and sent in the trial request. The server stores it and checks uniqueness.

### 5.5 Tier Gating in Gateway

```
LicenseManager.getCurrentTier()
  → Read /opt/localcloud/ENFORCE_LICENSE
  → If "false" → return PRO (skip all checks)
  → If "true" → attempt key validation:
      → Check HMAC-signed cache → if valid JWT, extract tier
      → Check bypass mode (development)
      → Check offline key (lck_ prefix)
      → Call license server for online key (lco_ prefix)
```

Service gating is enforced at two levels:
1. **Startup**: `LocalCloudApplication.start()` disables services above current tier
2. **Runtime**: `AdminApiService.enableService()` rejects requests for services above current tier

---

## 6. Security Model

### 6.1 Trust Boundaries

```
Untrusted (User)                    Trusted (Container)
─────────────────                   ──────────────────
                                    ┌─────────────────────┐
  User's network ──HTTPS──▶         │  License Server     │
  (API calls)                       │  (port 9090)        │
                                    │                     │
  User's terminal ──STDIN──▶        │  PostgreSQL         │
  (CLI input)                       │  (port 5432)        │
                                    │                     │
                                    │  Admin SPA          │
                                    │  (embedded)         │
                                    └─────────────────────┘
```

### 6.2 Defense Layers

| Layer | Mechanism |
|---|---|
| **Network** | License server listens on `127.0.0.1:9090` (internal container), not exposed |
| **Auth** | Bearer token for all protected endpoints; admin session for admin endpoints |
| **Key Storage** | Keys hashed with SHA-256; raw key shown only once at creation |
| **JWT** | RS256 signed; verified by gateway with public key from env or server fetch |
| **Cache** | HMAC-SHA256 signed with derived key; tampering → treat as no cache |
| **Clock** | Last-seen timestamp + monotonic boot counter detect rollback |
| **Build** | `BUILD_MODE` baked into image; production mode disables bypass |
| **Config** | `ENFORCE_LICENSE` arg controls whether enforcement runs at all |
| **Code** | Validation spread across classes + supervisord gating prevents single-point bypass |

### 6.3 Admin Console Security

The admin SPA is embedded in the license server JAR and served at `/admin`. It is accessible only within the container (not exposed externally). Admin sessions are in-memory only — no persistence layer. The admin login uses a configured password (`ADMIN_PASSWORD` env var). No admin can be created without the password; there is no default.

---

## 7. Deployment

### 7.1 Embedded (Default)

```
Supervisord manages:
  1. postgresql
  2. license-gate.sh → localcloud-license-server (port 9090) → localcloud-server (port 8080)
  3. emulators (GCS, PubSub, etc.)
```

The license server starts before the gateway and is ready to accept validation requests by the time the gateway boots.

### 7.2 Standalone (Hosted)

```
docker run -p 9090:9090 \
  -e LOCALCLOUD_LICENSE_PRIVATE_KEY=... \
  -e LOCALCLOUD_POSTGRES_URL=... \
  -e ADMIN_PASSWORD=... \
  localcloud-license-server:latest
```

For the hosted `api.localcloud.dev` deployment, the license server runs as an independent service with its own PostgreSQL instance.

### 7.3 Environment Variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `LOCALCLOUD_POSTGRES_URL` | Yes | — | JDBC URL for PostgreSQL |
| `LOCALCLOUD_POSTGRES_USER` | Yes | — | PostgreSQL user |
| `LOCALCLOUD_POSTGRES_PASSWORD` | Yes | — | PostgreSQL password |
| `LOCALCLOUD_LICENSE_PRIVATE_KEY` | No | Ephemeral generation | Base64 DER PKCS8 RSA-2048 private key |
| `ADMIN_PASSWORD` | Yes | — | Admin console password |
| `LOCALCLOUD_SMTP_HOST` | No | — | SMTP server for OTP delivery |
| `LOCALCLOUD_SMTP_PORT` | No | 587 | SMTP port |
| `LOCALCLOUD_SMTP_USERNAME` | No | — | SMTP username |
| `LOCALCLOUD_SMTP_PASSWORD` | No | — | SMTP password |
| `LOCALCLOUD_SMTP_FROM` | No | — | From address for OTP emails |
| `LOCALCLOUD_SERVER_PORT` | No | 9090 | License server port |
| `ENFORCE_LICENSE` | No | true | Override enforcement (file-based, read by gateway) |

### 7.4 Build Checklist

```bash
# Build
cd localcloud-license-server && ./gradlew shadowJar

# Run standalone
java -jar build/libs/localcloud-license-server-*.jar

# Test
./gradlew test   # 89 tests
```

---

## 8. Test Architecture

| Layer | Technology | Count | Coverage |
|---|---|---|---|
| Unit tests | JUnit 5 + Mockito | 89 | All repositories, handlers, validators |
| Database tests | Testcontainers (PostgreSQL) | — | Schema init, CRUD operations |
| Validation tests | Mock Armeria | — | JWT roundtrip, expiry, offline key flow |

Test classes are organized to mirror the production package structure:
- `auth/` — `AuthRepositoryTest`, `SessionRepositoryTest`, `SessionAuthDecoratorTest`
- `keys/` — `ApiKeyRepositoryTest`
- `validation/` — `JwtSignerTest`, `KeyPairManagerTest`, `LicenseValidationTest`, `LicenseValidatorExpiryTest`
- `admin/` — `AdminHandlerTest`, `AdminStatsRepositoryTest`, `AdminSessionStoreTest`
- `trial/` — `TrialHandlerTest`, `TrialRepositoryTest`
- `db/` — `SchemaInitializerTest`

---

## 9. References

- `docs/licensing-security.md` — Security gap analysis and phased remediation
- `docs/plans/2026-05-10-api-key-licensing-design.md` — Original design document
- `docs/plans/2026-05-10-api-key-licensing-phase1-plan.md` — Phase 1 implementation plan
- `docs/plans/2026-05-11-phase2-license-server-plan.md` — Phase 2 (license server) plan
- `docs/plans/2026-05-11-phase1-fixes-and-hardening-plan.md` — Hardening plan
- `DEVELOPER_GUIDE.md` — Build instructions with ENFORCE_LICENSE toggle
- `localcloud-license-server/build.gradle` — Dependencies and build config
- `localcloud-license-server/src/main/resources/schema.sql` — Full database schema
