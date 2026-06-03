# License Model

> **Companion to SPEC.md.** License tiers, server architecture, and enforcement model.

## Tier Structure

| Tier | Price | Users | Service Access | Key Features |
|------|-------|-------|---------------|--------------|
| **Community** | Free | 1 | Limited subset | Local dev, training. Memorystore, Pub/Sub, GCS, basic facade services. |
| **Pro** | Paid | 1 | All 23+ services | Full emulator suite. CI/CD for individual use. |
| **Team** | Paid/seat | 2–50 | All services | Multi-seat license, CI/CD for team pipelines. |
| **Enterprise** | Custom | Unlimited | All services | Air-gapped, offline keys, audit trail, bulk provisioning, SSO. |

## License Server Architecture

**Process:** Standalone Java process (port 9090), own PostgreSQL schema.
**Location:** Embedded in Docker image, logically independent. Can also run externally at `api.localcloud.dev`.

### Features

| ID | Feature | Description |
|----|---------|-------------|
| F1 | User Registration & Auth | Email/password accounts, session management |
| F2 | API Key Management | Generate, revoke, rotate keys. Online + offline modes |
| F3 | License Validation | Gateway validates key on startup + periodic re-check |
| F4 | Trial Management | 14-day free trial for Pro tier, device-tied |
| F5 | Device Tracking | License key bound to device fingerprint |
| F6 | Admin Console | Web UI for key management, user admin, analytics |
| F7 | Tier Enforcement | Gateway checks JWT token tier before allowing service access |

### Validation Flow

1. Gateway starts → reads license key from config/env
2. Calls license server `POST /validate` with key + device fingerprint
3. On success: receives RS256 JWT token (4-hour expiry)
4. Gateway caches JWT, re-validates before expiry
5. Each service request: gateway checks JWT tier against required service tier
6. If tier insufficient: returns 403 Forbidden

### JWT Token Contents
```
{
  "sub": "user@example.com",
  "device_id": "abc123",
  "tier": "pro",
  "exp": 1717171200,
  "iat": 1717156800,
  "iss": "localcloud-license"
}
```

### Offline Mode (Enterprise)
- License key includes embedded signing public key
- Gateway validates JWT locally without calling license server
- Key rotation: new keys distributed via secure channel, old keys revoked in key manifest
- Audit trail: gateway logs license events locally, syncs when online

## Service Gating

Services are mapped to minimum required tier. Gateway enforces at request time.

| Service | Minimum Tier |
|---------|-------------|
| Memorystore | Community |
| Pub/Sub | Community |
| GCS | Community |
| Firestore | Community |
| Secret Manager | Community |
| Cloud Logging | Community |
| Cloud Monitoring | Community |
| BigQuery | Pro |
| Bigtable | Pro |
| Spanner | Pro |
| Cloud Tasks | Pro |
| GKE | Pro |
| Compute Engine | Pro |
| Cloud Run | Pro |
| Cloud Workflows | Pro |
| Cloud Scheduler | Pro |
| Cloud Functions | Pro |
| AlloyDB | Pro |
| Dataproc | Pro |
| Cloud IAM | Pro |
| Cloud KMS | Pro |
| Cloud SQL | Pro |

## Non-Functional Requirements

- License validation latency < 50ms (cached) or < 500ms (remote)
- 99.9% uptime for hosted license server
- JWT signature verification must not require network call (offline mode)
- Key revocation must propagate to all active gateways within 1 hour
- Admin API audit trail with timestamps and actor IDs
