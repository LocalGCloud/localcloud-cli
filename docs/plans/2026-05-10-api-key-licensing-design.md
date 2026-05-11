# LocalCloud API Key & Licensing System Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:writing-plans to create the implementation plan.

**Goal:** Add API key authentication with monetization tiers, device fingerprinting, and offline-capable license validation to LocalCloud.

**Architecture:** Self-hosted license server (api.localcloud.dev) issues signed tokens validated client-side. Two key modes: online (periodic server validation) and offline (self-validating Ed25519 signed tokens). Device fingerprinting prevents trial abuse.

**Tech Stack:** Java 21 (client-side validation), Ed25519 (offline keys), RS256 JWT (online keys), PostgreSQL (license server), Stripe (billing)

---

## 1. System Components

### 1.1 License Server (`api.localcloud.dev`)

Self-hosted service handling user registration, API key generation, trial management, device tracking, and Stripe billing.

**Database schema:**

```sql
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    email_verified BOOLEAN DEFAULT FALSE,
    created_at    TIMESTAMPTZ DEFAULT now(),
    status        TEXT DEFAULT 'active'  -- active, suspended, deleted
);

CREATE TABLE api_keys (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID REFERENCES users(id),
    key_hash   TEXT NOT NULL,            -- SHA-256 of the raw key
    key_prefix TEXT NOT NULL,            -- first 8 chars for identification
    tier       TEXT NOT NULL,            -- trial, community, pro, team
    mode       TEXT NOT NULL DEFAULT 'online',  -- online, offline
    created_at TIMESTAMPTZ DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE TABLE devices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID REFERENCES users(id),
    device_fingerprint TEXT NOT NULL,
    first_seen         TIMESTAMPTZ DEFAULT now(),
    last_seen          TIMESTAMPTZ DEFAULT now(),
    UNIQUE(user_id, device_fingerprint)
);

CREATE TABLE trials (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID REFERENCES users(id),
    device_fingerprint TEXT NOT NULL UNIQUE,  -- one trial per device ever
    started_at         TIMESTAMPTZ DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE subscriptions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID REFERENCES users(id),
    stripe_subscription_id TEXT UNIQUE,
    tier                   TEXT NOT NULL,
    status                 TEXT NOT NULL,  -- active, past_due, canceled
    current_period_end     TIMESTAMPTZ,
    created_at             TIMESTAMPTZ DEFAULT now()
);
```

**API endpoints:**

| Endpoint | Method | Purpose |
|---|---|---|
| `/auth/register` | POST | Email + password registration |
| `/auth/verify` | POST | OTP email verification |
| `/auth/login` | POST | Get session token |
| `/keys/generate` | POST | Create API key (shown once) |
| `/keys/list` | GET | List user's keys (prefix only) |
| `/keys/revoke` | POST | Revoke a key |
| `/license/validate` | POST | Validate online key + device → signed JWT |
| `/trial/start` | POST | Start trial (checks device fingerprint) |
| `/billing/checkout` | POST | Create Stripe checkout session |
| `/billing/webhook` | POST | Stripe webhook handler |
| `/billing/portal` | GET | Stripe customer portal URL |

### 1.2 Client-Side Validation (Inside Docker Container)

Runs during container startup in `LocalCloudApplication.java`. Validates the API key, checks device fingerprint, and gates service access.

### 1.3 CLI Registration Flow

Interactive terminal flow during first `docker run` when no key is provided.

---

## 2. Device Fingerprinting

### 2.1 Hardware Signals

Collected inside the container at runtime:

| Signal | Source | Stability |
|---|---|---|
| CPU model + core count | `/proc/cpuinfo` | Stable unless hardware change |
| Total RAM | `/proc/meminfo` | Stable |
| MAC address of primary NIC | `/sys/class/net/*/address` | Stable per Docker Desktop VM |
| Disk serial | `/sys/block/*/serial` | Stable if available |
| Host kernel version | `uname -r` | Stable between OS upgrades |

### 2.2 Fingerprint Computation

```
raw = cpu_model + ":" + cores + ":" + ram_mb + ":" + mac + ":" + disk_serial
device_id = SHA-256(raw)
```

Computed in Java at runtime — never stored in any user-editable file. Same physical machine always produces same fingerprint regardless of container rebuilds or volume deletion.

### 2.3 Limitations

- Docker Desktop VMs can share similar hardware signals across different host machines in some configurations
- RAM changes or NIC changes will change fingerprint (treated as new device)
- Acceptable trade-off: false "new device" is better than false "same device"

---

## 3. Key Modes

### 3.1 Online Keys (`lco_` prefix)

Standard flow for internet-connected environments.

**Format:** `lco_<random-32-bytes-base64url>` (~48 characters)

**Validation flow:**
1. Client sends `POST /license/validate` with `{key, device_id}`
2. Server verifies key hash, checks tier/subscription status, records device
3. Server returns RS256-signed JWT (valid 4 hours)
4. Client caches JWT in HMAC-signed binary file
5. Re-validates every 4 hours while running
6. **Offline grace period:** if server unreachable, cached JWT honored for max 72 hours

**JWT claims:**
```json
{
  "sub": "user@email.com",
  "device_id": "sha256:abc123...",
  "tier": "pro",
  "features": ["all"],
  "iat": 1718000000,
  "exp": 1718014400
}
```

### 3.2 Offline Keys (`lck_` prefix)

Self-validating tokens for air-gapped / enterprise environments. No network needed ever.

**Algorithm: Ed25519 digital signatures**

Why Ed25519:
- Asymmetric — private key never leaves license server, public key embedded in JAR
- Compact — 64-byte signatures, total key ~150-200 characters (copy-pasteable)
- Fast — verification is ~50μs
- Built into Java 15+ (`java.security.EdDSA`) — no external dependencies

**Key format:**
```
lck_<base64url(version:cbor_payload:ed25519_signature)>

version   = 1 byte (key format version, currently 0x01)
payload   = CBOR-encoded claims (compact binary, ~80-100 bytes)
signature = 64 bytes Ed25519
```

**Payload claims:**
```json
{
  "email": "user@company.com",
  "tier": "pro",
  "device_id": "sha256:abc123...",  // optional — omit for floating license
  "issued": 1718000000,
  "expires": 1720000000,
  "features": "all",
  "offline": true
}
```

**Verification (client-side, Java 21):**
```java
KeyFactory kf = KeyFactory.getInstance("EdDSA");
PublicKey pub = kf.generatePublic(new EdECPublicKeySpec(
    NamedParameterSpec.ED25519, edPoint));
Signature sig = Signature.getInstance("Ed25519");
sig.initVerify(pub);
sig.update(payloadBytes);
boolean valid = sig.verify(signatureBytes);
```

**Offline key properties:**
- Hard expiry baked into signed payload — cannot be extended without new key
- Optionally device-bound (device fingerprint in signed payload)
- Issued manually by admin or via self-service portal
- Priced at enterprise tier (harder to control usage)

---

## 4. Tamper Resistance

### 4.1 Signed License Tokens

Both online (JWT/RS256) and offline (Ed25519) keys use asymmetric cryptography. Public key embedded in compiled Java bytecode. User cannot forge valid tokens without private key.

### 4.2 Cached State Protection

License cache file at `$DATA_DIR/.license/token.bin`:
- Binary format (not human-readable)
- HMAC-SHA256 signed with key derived from `HMAC-SHA256(embedded_secret, device_fingerprint)`
- Contains: cached JWT + grace period metadata + last-seen timestamp + boot counter
- Tampering invalidates HMAC → treated as no cache

### 4.3 Clock Tamper Detection (Offline Keys)

System clock rollback is the primary attack vector for offline keys. Four layers of defense:

1. **Last-seen timestamp** — on every boot, write current timestamp to HMAC-signed file. If `current_time < last_seen` → "clock moved backwards" error → refuse to start
2. **Monotonic boot counter** — increment on each boot. Counter can only increase. Stored alongside timestamp in signed file
3. **Build timestamp floor** — compile `MIN_TIMESTAMP` into JAR at build time. Time can never be before build date
4. **Opportunistic time anchoring** — if any network request succeeds (even non-license), check TLS certificate `notBefore` date as trusted time source

### 4.4 Code-Level Protections

| Protection | Implementation |
|---|---|
| No single kill switch | Validation logic spread across multiple classes |
| Service gating | Each emulator subprocess checks license via supervisord env before launching |
| JAR integrity | SHA-256 checksum of server JAR verified at startup |
| Certificate pinning | HTTPS + pinned cert for license server validation endpoint |
| Obfuscation | Critical validation classes processed with ProGuard (optional, future) |

### 4.5 Attack Surface Analysis

| Attack | Defense |
|---|---|
| Edit cached token file | HMAC validation fails → treated as no cache |
| Delete data volume | Device fingerprint stays same → server knows trial used |
| Modify server JAR | JAR checksum validation at startup |
| Patch out license check | Validation spread across multiple classes + supervisord gating |
| New email, same machine | Device fingerprint blocks second trial |
| New machine, same email | Allowed (one trial per device, key valid on registered devices) |
| Replay old valid JWT | Expiry checked + periodic re-validation |
| MITM license server | HTTPS + certificate pinning |
| Run without network forever | 72h offline grace (online keys), hard expiry (offline keys) |
| Roll back system clock | Monotonic counter + last-seen timestamp + build floor |
| Copy key to other machine | Device-bound keys include device_id in signed payload |

---

## 5. Tier Structure

| Tier | Duration | Services | Key Mode | Price |
|---|---|---|---|---|
| **Trial** | 14 days | All 14 services | Online only | Free |
| **Community** | Forever | GCS + PubSub + Firestore | Online only | Free |
| **Pro** | Monthly/Annual | All 14 services | Online or Offline | Paid |
| **Team** | Monthly/Annual | All + multi-seat + priority support | Online or Offline | Paid |
| **Enterprise** | Custom | All + floating offline keys + SLA | Offline | Custom |

After trial expiry without payment → auto-downgrade to Community tier (3 services, not fully blocked).

---

## 6. Container Startup Flow

```
1. Compute device fingerprint (SHA-256 of hardware signals)

2. Check LOCALCLOUD_API_KEY env var

   ├── Starts with "lck_" → OFFLINE KEY
   │   ├── Split → payload + signature
   │   ├── Ed25519 verify with embedded public key
   │   ├── Verify expiry > now (with clock-tamper guards)
   │   ├── Verify device_id matches (if present in payload)
   │   ├── Valid → set tier env vars → start all permitted services
   │   └── Invalid/expired → print renewal URL → exit(1)
   │
   ├── Starts with "lco_" → ONLINE KEY
   │   ├── POST /license/validate {key, device_id}
   │   ├── Valid → receive JWT → cache to token.bin → start services
   │   ├── Invalid/revoked → print error → exit(1)
   │   └── Server unreachable → check cached JWT
   │       ├── Cache valid + within 72h grace → start services
   │       └── No cache or expired → print error → exit(1)
   │
   └── Absent → INTERACTIVE FIRST-RUN
       ├── Print banner: "Welcome to LocalCloud"
       ├── Prompt: "Enter email to start 14-day free trial: "
       ├── POST /trial/start {email, device_id}
       │   ├── New device → send OTP email
       │   │   ├── Prompt: "Enter verification code: "
       │   │   ├── POST /auth/verify {email, otp}
       │   │   ├── Issue trial key → print it → set env → start
       │   │   └── "Save this key: LOCALCLOUD_API_KEY=lco_xxx"
       │   └── Known device → "Trial already used on this machine"
       │       └── "Get a key at https://localcloud.dev/pricing" → exit(1)
       └── Or: "Set LOCALCLOUD_API_KEY=<key> for existing license"

3. Background: re-validate online keys every 4 hours
```

---

## 7. Environment Variables

| Variable | Purpose |
|---|---|
| `LOCALCLOUD_API_KEY` | Online (`lco_...`) or offline (`lck_...`) license key |
| `LOCALCLOUD_LICENSE_SERVER` | Override license server URL (default: `https://api.localcloud.dev`) |
| `LOCALCLOUD_OFFLINE_GRACE_HOURS` | Override offline grace period (default: 72, compile-time for tamper resistance) |

---

## 8. Implementation Phases

### Phase 1: Client-Side Key Validation (no server yet)
- Device fingerprinting
- Ed25519 offline key generation (CLI tool) and validation
- License check integrated into startup flow
- Service gating by tier
- Tamper-resistant cache file

### Phase 2: License Server MVP
- User registration + email OTP
- Online key generation and validation
- Trial management with device tracking
- Basic admin dashboard

### Phase 3: Billing Integration
- Stripe checkout + webhook handling
- Subscription lifecycle (create, upgrade, cancel, past_due)
- Auto-downgrade on expiry
- Customer portal

### Phase 4: Hardening
- Certificate pinning
- JAR checksum validation
- Clock tamper detection improvements
- Throwaway email detection
- Rate limiting and abuse prevention

---

## 9. Open Questions

1. **CBOR vs JSON for offline key payload** — CBOR is more compact (~30% smaller keys) but adds a dependency. JSON keeps it simpler. Recommendation: start with JSON, switch to CBOR if key length becomes unwieldy.
2. **Multi-device policy for paid keys** — how many devices per Pro license? Recommendation: 3 devices per seat.
3. **Key rotation** — should paid keys rotate periodically? Recommendation: no forced rotation, but support manual rotation via portal.
4. **Team seat management** — admin dashboard for team owners to manage seats/devices? Defer to Phase 3+.
