# Implementation Plan: Cloud KMS & Cloud IAM Console Makeover

**Date:** 2026-05-27  
**Reference:** Secret Manager console design pattern  
**Scope:** Backend admin API + frontend Data Explorer UI

---

## Executive Summary

Cloud KMS has a fully functional REST facade backend (`KmsEmulator`, `KmsStore`, `KmsRestService`) but **zero console integration**. Cloud IAM has a basic console page (simple policy table) that needs a Google Console-aligned redesign. Both will follow the **Secret Manager drill-down pattern**: list → detail → resource management with state badges, actions, and breadcrumbs.

---

## Current State Assessment

### Cloud KMS
| Layer | Status | Detail |
|-------|--------|--------|
| Backend REST API | ✅ Complete | Key rings, crypto keys, versions, encrypt/decrypt, primary version rotation |
| PostgreSQL schema | ✅ Complete | `kms_key_rings`, `kms_crypto_keys`, `kms_crypto_key_versions` |
| Admin Browse API | ❌ Missing | No `browseKms()` in `BrowseService.java` |
| Admin Mutate API | ❌ Missing | No `mutateKms()` in `MutateService.java` |
| Console tab | ❌ Missing | Not in `DataBrowser.jsx` TABS array |
| Console view | ❌ Missing | No `KmsView` component |
| API client | ❌ Missing | No KMS methods in `api.js` |
| Service registry | ✅ Complete | `services.yaml` entry exists, `defaultEnabled: false` |

### Cloud IAM
| Layer | Status | Detail |
|-------|--------|--------|
| Backend gRPC facade | ✅ Complete | `testIamPermissions` returns ALLOW, `getIamPolicy`/`setIamPolicy` manage JSONB policies |
| Admin Browse API | ✅ Basic | `browseCloudIAM()` returns flat policy list |
| Admin Mutate API | ✅ Basic | `mutateCloudIAM()` supports create/delete policy |
| Console tab | ✅ Present | `cloudiam` in TABS |
| Console view | ⚠️ Minimal | `CloudIAMView` — flat table only, no drill-down |

---

## Google Cloud Console Reference

### Cloud KMS Layout (console.cloud.google.com/security/kms)
```
Key rings (list)
  ├── Key ring name
  ├── Location (e.g., us-central1, global)
  └── Created

  Drill-down → Key ring detail
    ├── Crypto keys (list)
    │   ├── Key name
    │   ├── Purpose (ENCRYPT_DECRYPT, ASYMMETRIC_SIGN, etc.)
    │   ├── Algorithm
    │   ├── Primary version
    │   └── Created
    │
    │   Drill-down → Crypto key detail
    │     ├── [Versions] tab (active by default)
    │     │   ├── Version number
    │     │   ├── State (Enabled / Disabled / Destroyed / Scheduled for destruction)
    │     │   ├── Algorithm
    │     │   ├── Protection level
    │     │   └── Created
    │     │   Actions: Enable / Disable / Destroy / Set as primary
    │     ├── [Details] tab
    │     │   ├── Purpose, Algorithm, Labels, Rotation period
    │     └── [Permissions] tab (IAM)
```

**Key observations for localcloud applicability:**
- No SQL editor needed (confirmed by user)
- Key operations: Create key ring, Create crypto key, Version lifecycle (enable/disable/destroy), Set primary version
- No rotation schedule (not implemented in backend)
- Only `ENCRYPT_DECRYPT` + `GOOGLE_SYMMETRIC_ENCRYPTION` supported per `KmsRestService`

### Cloud IAM Layout (console.cloud.google.com/iam-admin/iam)
```
IAM & Admin → IAM
  ├── Principals (list)
  │   ├── Member (user, service account, group)
  │   ├── Roles assigned
  │   ├── Resource
  │   └── Inheritance
  │
  ├── [Grant Access] button
  ├── [Remove] action per binding
  │
  Service Accounts (separate tab/page)
  ├── Account name
  ├── Email
  ├── Status
  └── Actions
```

**Key observations for localcloud applicability:**
- Current IAM is a permissive stub — no real authentication/authorization
- Focus on policy **visibility** and basic **CRUD**, not complex IAM workflows
- Display role bindings in a human-readable format (expand JSONB policy)
- Group by resource or principal for clarity

---

## Phase 1: Backend — Cloud KMS Admin API

### 1.1 BrowseService.java — Add `browseKms()`
**File:** `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`

Add to `browseService()` switch:
```java
case "kms" -> browseKms(resourceType, resourceId, projectId);
```

Implement `browseKms()` with three browse paths:

| Path | Description | Returns |
|------|-------------|---------|
| `browse/kms` | List all key rings for project | `{ "keyRings": [...] }` |
| `browse/kms/keys/{keyRingId}` | List crypto keys in a key ring | `{ "keyRing": "...", "cryptoKeys": [...] }` |
| `browse/kms/versions/{keyRingId}/{cryptoKeyId}` | List versions of a crypto key | `{ "cryptoKey": "...", "versions": [...] }` |

Query patterns (direct PostgreSQL, same as secretmanager):
```sql
-- Key rings
SELECT key_ring_id, location_id, created_at FROM kms_key_rings
WHERE project_id = ? ORDER BY key_ring_id;

-- Crypto keys
SELECT crypto_key_id, purpose, algorithm, primary_version, labels, created_at
FROM kms_crypto_keys
WHERE project_id = ? AND key_ring_id = ? ORDER BY crypto_key_id;

-- Versions
SELECT version_number, state, algorithm, created_at
FROM kms_crypto_key_versions
WHERE project_id = ? AND key_ring_id = ? AND crypto_key_id = ?
ORDER BY version_number DESC;
```

### 1.2 MutateService.java — Add `mutateKms()`
**File:** `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`

Add to both `mutate()` and `mutateWithSubOp()` switches:
```java
case "kms" -> mutateKms(operation, subOp, json);
```

Implement `mutateKms()` operations:

| Operation | SubOp | Action | Body Fields |
|-----------|-------|--------|-------------|
| `keyrings` | — | Create key ring | `keyRingId`, `locationId` |
| `keys` | — | Create crypto key | `keyRingId`, `cryptoKeyId`, `locationId` |
| `versions` | `enable` | Enable version | `keyRingId`, `cryptoKeyId`, `version`, `locationId` |
| `versions` | `disable` | Disable version | `keyRingId`, `cryptoKeyId`, `version`, `locationId` |
| `versions` | `destroy` | Destroy version | `keyRingId`, `cryptoKeyId`, `version`, `locationId` |
| `versions` | `setPrimary` | Set primary version | `keyRingId`, `cryptoKeyId`, `version`, `locationId` |
| `keys` | `delete` | Delete crypto key + versions | `keyRingId`, `cryptoKeyId`, `locationId` |

> **Note:** For mutations, call the existing `KmsStore` methods directly (it's already in the same JVM), or issue internal REST calls to `KmsRestService`. Using `KmsStore` directly is simpler and avoids HTTP overhead.

### 1.3 SeedService.java — Add KMS seeding (optional)
**File:** `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java`

Add `seedKms()` and `resetKms()` if seed YAML support is desired. Follow the pattern used by `seedSecretManager()`.

---

## Phase 2: Backend — Cloud IAM Admin API Enhancement

### 2.1 BrowseService.java — Enhance `browseCloudIAM()`
**File:** `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`

Current `browseCloudIAM()` returns a flat policy list with `policy: "stored"` placeholder. Enhance to:

| Path | Description | Returns |
|------|-------------|---------|
| `browse/cloudiam` | List all policies (enhanced) | `{ "policies": [...] }` with parsed bindings |
| `browse/cloudiam/principals` | Group by principal | `{ "principals": [...] }` |

Parse the protobuf `policy_proto` field into readable JSON:
```java
// Parse protobuf Policy from iam_policies table
com.google.iam.v1.Policy policy = com.google.iam.v1.Policy.parseFrom(policyBytes);
List<Map<String, Object>> bindings = new ArrayList<>();
for (Binding b : policy.getBindingsList()) {
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("role", b.getRole());
    binding.put("members", b.getMembersList());
    bindings.add(binding);
}
```

### 2.2 MutateService.java — Enhance `mutateCloudIAM()`
Add support for more granular operations:

| Operation | SubOp | Action |
|-----------|-------|--------|
| `policies` | — | Create/replace policy (existing) |
| `policies` | `delete` | Delete policy (existing) |
| `bindings` | `add` | Add a role binding to existing policy |
| `bindings` | `remove` | Remove a role binding |

---

## Phase 3: Frontend — API Client

### 3.1 api.js — Add KMS methods
**File:** `localcloud-console/src/api.js`

Add to `api` object:
```javascript
// KMS
kmsKeyRings: () => get(appendProject('/browse/kms')),
kmsCryptoKeys: (keyRingId) => get(appendProject(`/browse/kms/keys/${encodeURIComponent(keyRingId)}`)),
kmsVersions: (keyRingId, cryptoKeyId) => get(appendProject(`/browse/kms/versions/${encodeURIComponent(keyRingId)}/${encodeURIComponent(cryptoKeyId)}`)),

// KMS mutations
kmsCreateKeyRing: (data) => postJson(appendProject('/mutate/kms/keyrings'), data),
kmsCreateCryptoKey: (data) => postJson(appendProject('/mutate/kms/keys'), data),
kmsEnableVersion: (data) => postJson(appendProject('/mutate/kms/versions/enable'), data),
kmsDisableVersion: (data) => postJson(appendProject('/mutate/kms/versions/disable'), data),
kmsDestroyVersion: (data) => postJson(appendProject('/mutate/kms/versions/destroy'), data),
kmsSetPrimaryVersion: (data) => postJson(appendProject('/mutate/kms/versions/setPrimary'), data),
kmsDeleteCryptoKey: (data) => postJson(appendProject('/mutate/kms/keys/delete'), data),
```

### 3.2 api.js — Add IAM methods (enhanced)
```javascript
// IAM enhanced
iamPrincipals: () => get(appendProject('/browse/cloudiam/principals')),
iamAddBinding: (data) => postJson(appendProject('/mutate/cloudiam/bindings/add'), data),
iamRemoveBinding: (data) => postJson(appendProject('/mutate/cloudiam/bindings/remove'), data),
```

---

## Phase 4: Frontend — DataBrowser.jsx

### 4.1 Add KMS Tab
**File:** `localcloud-console/src/pages/DataBrowser.jsx`

Add to `TABS` array (alphabetically or grouped with security services):
```javascript
{ id: 'kms', label: 'Cloud KMS' },
```

Add to `SERVICE_WHITELIST` or equivalent browse enablement list.

Add to the main switch that renders views:
```javascript
case 'kms': return <KmsView data={data} onAdd={handleAdd} onDelete={handleDelete} />;
```

### 4.2 Create `KmsView` Component
**Pattern:** Same as `SecretManagerView` — drill-down with `<Show when={!selectedX()}>` pattern.

#### Hierarchy State:
```javascript
const [selectedKeyRing, setSelectedKeyRing] = createSignal(null);
const [selectedCryptoKey, setSelectedCryptoKey] = createSignal(null);
const [cryptoKeys, setCryptoKeys] = createSignal([]);
const [versions, setVersions] = createSignal([]);
```

#### View 1: Key Rings List
- Table columns: **Key Ring Name**, **Location**, **Created**, **Actions** (Delete)
- Click row → drill into crypto keys
- "+ Create Key Ring" button

#### View 2: Crypto Keys List (within selected key ring)
- Back link (← Back to key rings)
- Table columns: **Key Name**, **Purpose**, **Algorithm**, **Primary Version**, **Created**, **Actions**
- Click row → drill into versions
- "+ Create Crypto Key" button

#### View 3: Versions List (within selected crypto key)
- Back link (← Back to crypto keys)
- Table columns: **Version**, **State** (badge), **Algorithm**, **Created**, **Actions**
- Actions per version:
  - `ENABLE` → button (if DISABLED)
  - `DISABLE` → button (if ENABLED)
  - `DESTROY` → danger button (if not DESTROYED)
  - `Set Primary` → button (if ENABLED and not already primary)
- State badges: `badge-healthy` (ENABLED), `badge-warning` (DISABLED), `badge-unhealthy` (DESTROYED)

### 4.3 Redesign `CloudIAMView` Component

Current view is a flat table of raw policies. Redesign to:

#### Option A: Principals-First View (Recommended)
Group policies by principal (member), matching Google Console's default view:

```
┌─────────────────────────────────────────────────────────────┐
│  Principals                                    [+ Grant]    │
├─────────────────────────────────────────────────────────────┤
│  user:alice@example.com                                     │
│    ├── roles/storage.admin  (resource: gcs/bucket-1)        │
│    └── roles/viewer         (resource: project)             │
│                                                             │
│  serviceAccount:sa-1@...                                    │
│    └── roles/editor         (resource: project)             │
└─────────────────────────────────────────────────────────────┘
```

**Implementation:**
- Transform flat `policies` array into `principals` map in the component
- Each principal card shows member + list of role bindings
- "Grant Access" modal: resource, role, members (comma-separated)
- "Remove" action per binding

#### Option B: Resource-First View (Alternative / Tab)
Keep current view but enhanced with parsed bindings displayed as chips/tags.

---

## Phase 5: Frontend — services.js Updates

### 5.1 Update SERVICE_META
**File:** `localcloud-console/src/data/services.js`

`kms` entry already exists. Ensure `cloudiam` description is updated:
```javascript
cloudiam: { label: 'Cloud IAM', description: 'Identity and Access Management. View and manage IAM policies, role bindings, and service account access.' },
```

### 5.2 SQL_SERVICES
`kms` and `cloudiam` already exist in `SQL_SERVICES`. No changes needed unless adding KMS placeholder.

### 5.3 SERVICE_SCHEMAS
`kms` and `cloudiam` schemas already exist. No changes needed.

---

## Phase 6: Testing

### 6.1 Backend Tests
- **BrowseServiceTest** (if exists): Add `testBrowseKms()` for key rings, crypto keys, versions
- **MutateServiceTest** (if exists): Add `testMutateKms()` for create key ring, create key, version state changes
- **KmsRestServiceTest** already exists — verify no regressions

### 6.2 Frontend Tests
- Manual console verification:
  1. Enable KMS service
  2. Navigate to Data Explorer → Cloud KMS tab
  3. Create key ring → create crypto key → view versions
  4. Enable/disable/destroy versions
  5. Set primary version
  6. Delete crypto key
- Verify Cloud IAM tab shows redesigned principals view

### 6.3 Build Verification
```bash
cd localcloud-server && ./gradlew build
cd localcloud-console && npm run build
```

---

## File Change Summary

| File | Action | Lines (est.) |
|------|--------|-------------|
| `BrowseService.java` | Add `browseKms()`, enhance `browseCloudIAM()` | +120, ~+20 |
| `MutateService.java` | Add `mutateKms()`, enhance `mutateCloudIAM()` | +150, ~+40 |
| `SeedService.java` | Add `seedKms()` / `resetKms()` (optional) | +60 |
| `CapabilityCatalog.java` | Update capability matrix for kms | ~+5 |
| `api.js` | Add KMS + IAM API methods | +25 |
| `DataBrowser.jsx` | Add `kms` tab, create `KmsView`, redesign `CloudIAMView` | +350, ~+80 |
| `services.js` | Update descriptions if needed | ~+2 |

---

## Design Decisions

### Why No SQL Editor for KMS?
KMS is a **resource management** service, not a data query service. Users create/manage key rings and crypto keys; they do not run SQL against them. The console should reflect the Google Cloud KMS console's management-oriented layout.

### Why Secret Manager Pattern?
Secret Manager and KMS share the same conceptual model:
- **Container** (Secret = Key Ring)
- **Resource** (Secret Version = Crypto Key)
- **Version lifecycle** (enable/disable/destroy)
The drill-down UX pattern translates cleanly.

### IAM Scope Boundaries
LocalCloud IAM is a **permissive stub** — `testIamPermissions` returns ALL permissions. The console redesign focuses on **policy visibility** (making JSONB policies human-readable) and basic **binding management**, not real RBAC enforcement.

---

## Open Questions / Risks

1. **Location handling:** Google KMS uses `global` or specific regions (`us-central1`). The backend currently stores `location_id`. Should the console default to `global` or require explicit location input?
   - *Recommendation:* Default to `global` for simplicity, allow override in create forms.

2. **KMS encrypt/decrypt in console?** Google Console does not expose encrypt/decrypt in the web UI (it's API-only). Should localcloud follow this?
   - *Recommendation:* Skip encrypt/decrypt UI. Keep it API-only via SDK/CLI, matching Google Console.

3. **IAM principal grouping:** The backend stores policies per resource, not per principal. The frontend must transform resource-oriented data into principal-oriented views. This is pure UI logic (no backend change needed for read).

4. **Service enablement:** KMS is `defaultEnabled: false` in `services.yaml`. Users must enable it before use. Should the console show an enablement prompt?
   - *Recommendation:* Yes — show a card with "Enable Cloud KMS" button when service is disabled, matching other disabled services.

---

## Next Steps

1. ✅ **This plan approved** → Begin Phase 1 (Backend: BrowseService + MutateService for KMS)
2. Backend: BrowseService + MutateService for IAM enhancements
3. Frontend: api.js updates
4. Frontend: KmsView + CloudIAMView components
5. Build + test + iterate
