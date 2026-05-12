# Licensing Security Architecture

**Implemented:** 2026-05-11
**Status:** Complete (Phases 1–6)

## Problem Summary

Six security gaps existed before this work:

| ID | Severity | Issue |
|----|----------|-------|
| P0 | Critical | License check ran inside `localcloud-server` — external emulators started by supervisord regardless |
| P0 | Critical | `/keys/generate`, `/keys/list`, `/keys/revoke` trusted email from request body (unauthenticated) |
| P1 | High | `/license/validate` returned plain JSON — no signature, MITM-injectable |
| P1 | High | Bypass mode (no key = PRO) was the default — unsafe for production images |
| P1 | High | Trial expiry recorded but never enforced — trial users got indefinite PRO access |
| P1 | High | Admin `/services/{id}/enable` had no tier check — community users could enable paid services |

---

## Architecture After Fix

### Container Startup Gate (Phase 1)

```
docker run ...
  └─ docker-entrypoint.sh
       ├─ [setup: CA certs, PostgreSQL init, service flags]
       ├─ license-gate.sh          ← NEW: runs BEFORE supervisord
       │    ├─ dev mode? → write "development" to /tmp/localcloud-tier, continue
       │    ├─ production + no key? → exit 1 (container never starts)
       │    └─ key present? → invoke LicenseGateMain via JVM, exit 0/1
       └─ exec gosu localcloud supervisord   ← only reached if gate passed
            ├─ gcs-emulator (port 4443)
            ├─ pubsub-emulator (port 8085)
            └─ ...all external emulators
```

Key file: `license-gate.sh` (repo root, copied to `/opt/localcloud/license-gate.sh`)
Key class: `com.localcloud.licensing.LicenseGateMain`

### Session-Based Key Management (Phase 2)

```
POST /auth/register   → creates user, sends OTP email
POST /auth/verify     → validates OTP, returns {session_token, expires_in_seconds}
POST /keys/generate   → requires Authorization: Bearer <session_token>
GET  /keys/list       → requires Authorization: Bearer <session_token>
POST /keys/revoke     → requires Authorization: Bearer <session_token>
```

Key classes: `SessionRepository`, `SessionAuthDecorator`
Schema: `sessions(token VARCHAR(512) PK, user_id UUID, created_at, expires_at)`

### RS256 Signed Validation (Phase 3)

```
Client                          License Server
  │                                    │
  ├─POST /license/validate {key, device}─→
  │                             validate key
  │                             sign JWT (RS256, iss=localcloud-license,
  │                                        tier, email, device_id, iat, exp)
  │←─────────────── {token: "<JWT>"} ─────
  │
  │  verify JWT signature (public key from env or /license/public-key)
  │  check: iss=localcloud-license, exp not past
  │  extract: tier, email, expires
```

Key classes: `JwtSigner`, `KeyPairManager`, `OnlineKeyValidator`
Key env vars:
- `LOCALCLOUD_LICENSE_PRIVATE_KEY` — base64 DER PKCS8 RSA-2048 private key (license server)
- `LOCALCLOUD_LICENSE_PUBLIC_KEY` — base64 DER X.509 public key (client)

### Production Mode Gate (Phase 4)

File `/opt/localcloud/BUILD_MODE` is baked into the Docker image at build time:

```dockerfile
ARG BUILD_MODE=development
RUN echo "${BUILD_MODE}" > /opt/localcloud/BUILD_MODE
```

Production CI/CD: `docker build --build-arg BUILD_MODE=production ...`

Behavior:
- `development` (default, missing file): bypass mode works — no key required
- `production`: `LOCALCLOUD_API_KEY` required; online key bypass disabled; gate exits 1 without key

Key method: `LicenseManager.isProductionBuild()` — reads file, checks system property `localcloud.buildModePath` for test override.

### Trial Expiry Enforcement (Phase 5)

```
POST /trial/start → inserts trials(user_id, device_fingerprint, expires_at=now+14d)
                  → issues api_key with tier="trial"

POST /license/validate →
  if tier == "trial":
    SELECT expires_at FROM trials WHERE user_id = ? ORDER BY started_at DESC LIMIT 1
    if null or expires_at < now() → 401 "Trial expired"
  if api_keys.expires_at != null and < now() → 401 "License expired"
```

Schema additions:
- `trials`: `idx_trials_user` index on `user_id`
- `api_keys`: `expires_at TIMESTAMP DEFAULT NULL` column

### License-Tier Service Gating (Phase 6)

Tier map in `services.yaml` (single source of truth):

```yaml
spanner:
  minTier: pro
gcs:
  minTier: community
```

Enforcement at two points:

1. **Startup** (`LocalCloudApplication.start()`): services with `minTier` above current tier are disabled via `config.setServiceEnabled(serviceId, false)`
2. **Runtime** (`AdminApiService`): `POST /_localcloud/services/{id}/enable` and `PUT /_localcloud/config/services` check tier before allowing enable

Response when blocked:
```json
{
  "error": "Service 'spanner' requires pro tier or higher",
  "current_tier": "community",
  "required_tier": "pro",
  "upgrade_url": "https://localcloud.dev/pricing"
}
```

Tier hierarchy: `COMMUNITY < TRIAL < PRO < TEAM < ENTERPRISE`

---

## Service Tier Map

| Service | minTier | Rationale |
|---------|---------|-----------|
| gcs | community | Core storage, available to all |
| pubsub | community | Core messaging |
| firestore | community | Core document DB |
| bigquery | community | Analytics |
| secretmanager | community | Dev secret management |
| cloudtasks | community | Task queuing |
| logging | community | Observability |
| monitoring | community | Observability |
| memorystore | community | Redis/Valkey cache |
| workflows | community | Workflow orchestration |
| bigtable | pro | Wide-column store — paid feature |
| spanner | pro | Distributed SQL — paid feature |
| gke | pro | Kubernetes emulation — paid feature |
| compute | pro | VM emulation — paid feature |
| cloudrun | pro | Container runtime — paid feature |
| vertexai | pro | AI/ML — paid feature |
| kms | pro | Cryptographic keys — paid feature |
| cloudsql | pro | Managed SQL — paid feature |

---

## Test Coverage

| Test Class | Module | Count | Scenarios |
|---|---|---|---|
| `LicenseGateMainTest` | server | 2 | Dev bypass exit-0; bad key format exit-1 |
| `ProductionModeTest` | server | 8 | Missing file→dev; "production"→enforced; case-insensitive; bypass blocked; LicenseManager + OnlineKeyValidator |
| `OnlineKeyValidatorTest` | server | 12 | JWT accept/reject; tampered/expired/wrong-key rejection; tier parsing; public-key cache (1 fetch) |
| `AdminServiceTierGatingTest` | server | 8 | Community blocked from pro; PRO/Enterprise allowed; unknown→404; disable always allowed; batch applied+blocked |
| `LicenseTierTest` | server | 10 | `includes()` all tier pairs; null requirement always true |
| `SessionRepositoryTest` | license-server | 6 | Create/validate; expire+validate=null; non-existent→false; null/blank/invalid→null |
| `SessionAuthDecoratorTest` | license-server | 4 | No header→401; invalid token→401; valid→sets attribute+delegates; DB error→500 |
| `JwtSignerTest` | license-server | 6 | RS256 roundtrip; wrong key rejected; null fields default; expired rejected; iss claim; iat present |
| `KeyPairManagerTest` | license-server | 5 | Ephemeral generation; base64 output non-blank; two instances differ; malformed env throws; X.509 format |
| `LicenseValidatorExpiryTest` | license-server | 6 | Expired trial; active trial; no trial record; expired subscription key; perpetual key (null expires_at); non-trial tier skips check |

**Total: 67 security-focused tests** across 10 test classes.

---

## Key Files

```
localcloud-server/src/main/java/com/localcloud/licensing/
  LicenseGateMain.java          — Container preflight entrypoint
  LicenseManager.java           — Validates license, reads BUILD_MODE, manages bypass
  OnlineKeyValidator.java       — Validates lco_ keys via license server, verifies JWT
  LicenseTierProvider.java      — Interface: provides current tier at runtime
  StaticLicenseTierProvider.java — Startup-time tier snapshot
  LicenseTier.java              — Enum: COMMUNITY→ENTERPRISE, includes() comparator

localcloud-server/src/main/java/com/localcloud/admin/
  AdminApiService.java          — Service toggle endpoints with tier gating

localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java
  — Wires LicenseTierProvider, startup service gating loop

license-gate.sh                 — Bash preflight gate (runs before supervisord)

localcloud-license-server/src/main/java/com/localcloud/license/
  auth/SessionRepository.java   — Session token lifecycle
  auth/SessionAuthDecorator.java — Armeria decorator enforcing Bearer auth
  auth/AuthHandler.java         — /auth/verify now returns session_token
  keys/ApiKeyHandler.java       — Uses session context, not email body
  validation/JwtSigner.java     — RS256 JWT signing (JJWT 0.12.x)
  validation/KeyPairManager.java — RSA key pair: env load or ephemeral fallback
  validation/LicenseValidator.java — Validates keys + enforces trial/subscription expiry
  validation/LicenseValidationHandler.java — Returns {token: JWT} not plain JSON
  trial/TrialRepository.java    — Trial record lookup with ORDER BY started_at DESC
```
