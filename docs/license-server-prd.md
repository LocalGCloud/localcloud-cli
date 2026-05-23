# License Server — Product Requirements Document

**Version:** 1.0
**Date:** 2026-05-21
**Status:** Draft

---

## 1. Product Overview

The LocalCloud License Server is a standalone authentication, authorization, and licensing microservice that controls access to LocalCloud's emulator suite. It manages user accounts, API keys (online and offline), trial entitlements, device tracking, and returns cryptographically signed validation tokens.

The server runs as a separate Java process (port 9090) with its own PostgreSQL schema. It is embedded in the LocalCloud Docker image but logically independent. It can also be hosted externally (e.g., `api.localcloud.dev`).

---

## 2. Problem Statement

LocalCloud's self-hosted Docker image bundles 14+ GCP emulators. Without a license system:

- **No user identity**: Anyone with the image gets full access — no way to track users or enforce entitlements.
- **No tier differentiation**: Community, Pro, Team, and Enterprise tiers all get the same experience.
- **No trial enforcement**: Users could evaluate PRO features indefinitely without paying.
- **No device binding**: License keys can be shared across machines without control.
- **No admin visibility**: No usage statistics, no key revocation, no audit trail.

The license server solves all of these while supporting both online (internet-connected) and offline (air-gapped) deployment modes.

---

## 3. Target Users & Personas

| Persona | Description | Needs |
|---|---|---|
| **Individual Developer** | Solo dev using LocalCloud locally for testing | Free tier, quick trial, easy setup |
| **Team Lead** | Small team sharing an instance | Tiered access, seat management |
| **Enterprise Admin** | Large org, air-gapped deployment | Offline keys, audit trail, bulk provisioning |
| **LocalCloud Operator** | Internal ops team running api.localcloud.dev | User management, billing, monitoring, key revocation |

---

## 4. Features

### F1: User Registration & Authentication
| ID | Feature | Priority |
|---|---|---|
| F1.1 | Email-based registration (`POST /auth/register`) | P0 |
| F1.2 | OTP email verification (`POST /auth/verify`) | P0 |
| F1.3 | Session token login (`POST /auth/login`) | P0 |
| F1.4 | Bearer-token session decorator on all protected endpoints | P0 |
| F1.5 | Configurable SMTP settings for OTP delivery | P1 |

### F2: API Key Management
| ID | Feature | Priority |
|---|---|---|
| F2.1 | Generate online keys (`POST /keys/generate`) — prefix `lco_` | P0 |
| F2.2 | Generate offline keys (`POST /keys/generate` with mode=offline) — prefix `lck_` | P0 |
| F2.3 | List user's keys (prefix only, never reveal full key) (`GET /keys/list`) | P0 |
| F2.4 | Revoke a key (`POST /keys/revoke`) | P0 |
| F2.5 | Key rotation support — revoke old + issue new in one operation | P1 |
| F2.6 | Admin CLI tool for offline key pair generation (`AdminCliKeyGen`) | P1 |

### F3: License Validation
| ID | Feature | Priority |
|---|---|---|
| F3.1 | Online key validation (`POST /license/validate`) — returns RS256-signed JWT | P0 |
| F3.2 | JWT caching with HMAC-signed cache file on client | P0 |
| F3.3 | 72-hour offline grace period for online keys | P0 |
| F3.4 | Hard expiry in offline keys (Ed25519 self-validating tokens) | P0 |
| F3.5 | Public key endpoint (`GET /license/public-key`) for client bootstrapping | P1 |
| F3.6 | Concurrent request deduplication — one validation at a time | P2 |

### F4: Trial Management
| ID | Feature | Priority |
|---|---|---|
| F4.1 | 14-day trial initiation (`POST /trial/start`) | P0 |
| F4.2 | Device-fingerprint deduplication — one trial per machine | P0 |
| F4.3 | Trial expiry enforcement during license validation | P0 |
| F4.4 | Graceful downgrade message on trial expiry | P1 |

### F5: Device Tracking
| ID | Feature | Priority |
|---|---|---|
| F5.1 | Device fingerprint collection (CPU, RAM, MAC, disk serial, kernel) | P0 |
| F5.2 | Device registration on first validation | P0 |
| F5.3 | Device-to-key binding for offline keys | P1 |
| F5.4 | Per-device key limit enforcement (e.g., 3 devices per Pro license) | P2 |

### F6: Admin Console
| ID | Feature | Priority |
|---|---|---|
| F6.1 | Admin login with session management | P0 |
| F6.2 | User listing and search | P0 |
| F6.3 | Key generation UI (all tiers) | P0 |
| F6.4 | Key revocation UI | P0 |
| F6.5 | Usage statistics dashboard (request counts per endpoint) | P0 |
| F6.6 | Trial status overview | P0 |
| F6.7 | Offline key generation (sign with server private key) | P1 |
| F6.8 | Email configuration UI | P2 |

### F7: Tier Enforcement in Gateway
| ID | Feature | Priority |
|---|---|---|
| F7.1 | Service tier map (`services.yaml` — single source of truth) | P0 |
| F7.2 | Startup service gating based on current tier | P0 |
| F7.3 | Runtime service-enable gating (reject upgrade if tier insufficient) | P0 |
| F7.4 | Configurable enforcement toggle (`ENFORCE_LICENSE` build arg) | P0 |
| F7.5 | Builder/CI-friendly bypass for development images | P0 |

---

## 5. Functional Requirements

### 5.1 License Keys

- **Online keys** (`lco_` prefix): Random 32 bytes base64url-encoded (~48 chars). Validated via server round-trip. Server returns RS256-signed JWT with tier, email, device_id, expiry.
- **Offline keys** (`lck_` prefix): Ed25519-signed payload containing email, tier, expiry, optional device binding. Self-validating — no server needed after issuance.
- Keys are stored hashed (SHA-256) in the database. The raw key is shown exactly once at creation time.
- Revoked keys are immediately invalid. Revocation is checked on every online validation.

### 5.2 Sessions

- Session tokens are opaque UUIDs with expiry (configurable, default 24h).
- Sessions are stored in the `sessions` table with `user_id`, `created_at`, `expires_at`.
- Bearer-token auth is enforced via an Armeria decorator (`SessionAuthDecorator`).
- Admin sessions are in-memory (`AdminSessionStore`) with periodic cleanup.

### 5.3 Trials

- Trials last 14 days from start.
- One trial per device fingerprint ever (cannot re-trial on same hardware).
- One trial per email address (duplicate email → existing trial returned or blocked).
- Trial expiry is checked during every license validation call.
- Expired trials return `401` with `"Trial expired"` message and `upgrade_url`.

### 5.4 Validation Response

- Successful validation returns `{ "token": "<RS256 JWT>" }`.
- The JWT contains: `sub` (email), `device_id`, `tier`, `exp`, `iat`, `iss=localcloud-license`.
- JWT expiry is 4 hours from issuance (client caches and re-validates at that interval).

### 5.5 Admin API

- Admin endpoints are protected by in-memory session tokens (`adm_` prefix).
- Admin stats are persisted to the `usage_metrics` table (UPSERT semantics, flushed every 30s).
- Admin can generate all key tiers regardless of user's current tier.
- Admin can revoke any key without affecting other keys for that user.

### 5.6 Service Gating

- Service tier requirements are defined in `services.yaml` (e.g., `spanner.minTier: pro`).
- On startup, any service whose `minTier` exceeds the current tier is disabled.
- The `POST /_localcloud/services/{id}/enable` endpoint checks tier and rejects with a descriptive error if insufficient.
- The `ENFORCE_LICENSE` build arg can disable all enforcement for development images.

---

## 6. Non-Functional Requirements

| Requirement | Target |
|---|---|
| **Validation latency** | < 500ms for online validation (server round-trip) |
| **Startup speed** | License check completes within 2s of container start |
| **Cache persistence** | HMAC-signed cache survives container restarts on same host |
| **Database durability** | All key/session/trial data survives container restart (PostgreSQL) |
| **Concurrency** | Handle 100+ concurrent validation requests |
| **Session cleanup** | Expired sessions pruned periodically (every 100 validations) |
| **Memory** | License server JVM heap ≤ 128 MB |
| **Key entropy** | Online keys use SecureRandom (256-bit minimum) |
| **Clock skew tolerance** | JWT validation allows 30s clock skew |

---

## 7. Key Flows

### 7.1 First-Run (No Key)

```
User starts container without LOCALCLOUD_API_KEY
  → license-gate.sh checks ENFORCE_LICENSE
  → If disabled: skip gate, start all services
  → If enabled:
      → Compute device fingerprint
      → Prompt for email or license key (interactive)
      → If email provided:
          → POST /auth/register → OTP sent to email
          → User enters OTP → POST /auth/verify
          → POST /trial/start → 14-day trial key issued
          → Container starts with PRO access
      → If key provided:
          → Validate key (online or offline)
          → Container starts at validated tier
```

### 7.2 Returning User (With Key)

```
User starts container with LOCALCLOUD_API_KEY=<key>
  → license-gate.sh checks BUILD_MODE and ENFORCE_LICENSE
  → If enforcement disabled: skip gate
  → If enforcement enabled:
      → If key starts with lck_: offline validation (Ed25519 verify)
      → If key starts with lco_: online validation (POST /license/validate)
          → Server returns JWT → cache to disk
      → Set tier env vars → start permitted services
      → Background: re-validate every 4 hours
```

### 7.3 Trial Expiry

```
Container running on trial key
  → Background re-validation triggers at 4-hour mark
  → POST /license/validate sends trial key
  → Server checks trials.expires_at < now()
  → Returns 401 with "Trial expired"
  → Container logs warning, continues running (no hard stop on runtime expiry)
  → Services above community tier disabled on next restart
```

### 7.4 Admin Key Generation

```
Admin logs into console (POST /admin/login)
  → Session token (adm_xxx) issued
  → Admin navigates to Keys page
  → Selects tier + email → POST /keys/generate
  → Server creates user if not exists
  → Generates lco_ key → stores hash → returns raw key
  → Admin copies raw key (shown once) and provides to user
```

---

## 8. Constraints & Assumptions

### Constraints

- **No Billing**: Stripe integration, checkout flows, and subscription management are deferred. Initially, key tiers are provisioned manually by admins.
- **No Password Auth**: Authentication is session-based (email OTP). Password-based login is not implemented.
- **Single Admin Console**: The admin SPA is embedded in the license server JAR (Solid.js), served at `/admin`.
- **Shared PostgreSQL**: The license server shares the PostgreSQL instance with the main gateway but uses a separate schema.
- **No Rate Limiting**: The `ratelimit` package exists but is not yet wired. Abuse prevention is deferred.

### Assumptions

- The Docker host's clock is reasonably accurate (NTP). Clock tampering is detected client-side but not server-side.
- SMTP is available for OTP delivery. If unavailable, admins can generate keys directly via the admin console.
- Device fingerprints are stable across container restarts on the same host (true for Docker, Docker Desktop, Linux hosts).
- Online keys require periodic internet access. For air-gapped environments, offline keys are used instead.

---

## 9. Future Scope

| Feature | Priority | Notes |
|---|---|---|
| Stripe billing integration | P1 | Webhook handling, subscription lifecycle, customer portal |
| Team seat management | P1 | Multi-seat licenses with admin dashboard |
| Rate limiting | P2 | Per-IP, per-key throttling on validation endpoint |
| Email template customization | P2 | Configurable OTP and notification email templates |
| Audit log | P2 | Full audit trail for all admin actions |
| SAML/SSO | P3 | Enterprise single sign-on |
| License usage reporting | P3 | Monthly usage reports emailed to admins |
