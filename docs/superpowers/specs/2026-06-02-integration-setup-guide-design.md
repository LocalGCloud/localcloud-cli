# Per-Service Integration Setup Guide

**Date:** 2026-06-02
**Status:** Design Approved
**Scope:** localcloud-console (Solid.js), localcloud-server (Java/Armeria)

## Overview

Add a **"Setup" tab** to every service page in the localcloud console. This tab shows the complete integration story for that service — how to connect SDKs, CLIs, and tools to the localcloud emulator — organized into three **integration levels** that the user can choose based on their needs.

Each service shows only the levels that apply to it. A Spanner page shows all three levels (SDK auto-detect works for most languages). A BigQuery page shows only Level 2 and Level 3 (no auto-detect exists), with Level 2 promoted as recommended.

## Goals

1. **Discoverable:** A developer exploring Spanner should find connection instructions on the Spanner page, not buried in a central docs page.
2. **Complete per service:** Every snippet, env var, language, and workaround for that service is in one place.
3. **Level-aware:** Instructions adapt based on what kind of emulator the service uses (standard Google emulator vs. localcloud-only emulator).
4. **No duplication:** The DNS proxy instructions (Level 3) appear on every service page but with clear "one-time setup" framing so the user knows it's shared infrastructure.

## The Three Integration Levels

Every service page shows the levels that apply to it, ordered by increasing effort/commitment:

| Level | Badge Color | What it means | When it applies |
|-------|-------------|---------------|-----------------|
| **Level 1 (Auto-detect)** | Green (`#16a34a`) | SDK reads `*_EMULATOR_HOST` automatically. Set the env var, nothing else. | Pub/Sub, Firestore, Bigtable, Spanner, GCS — for Python, Go, Node.js, Ruby, PHP |
| **Level 2 (Code endpoint)** | Blue (`#2563eb`) | Pass endpoint into client constructor. Small conditional function, one per service. | Java/C# for all services; all languages for localcloud-only emulators (BigQuery, Secret Manager, IAM, etc.) |
| **Level 3 (DNS proxy)** | Gray (`#6b7280`) | DNS redirect + reverse proxy. No application code changes. One-time infrastructure. | Universal fallback — covers every SDK, CLI, and tool in every language |

### When a level is unavailable

For services where a level doesn't apply (e.g., Level 1 on BigQuery), it's shown with:
- Strikethrough title
- Dashed border
- Reduced opacity (70%)
- Label: "Not available for [service]"

### Level promotion

When a service has no Level 1, the recommended level gets visual promotion:
- Blue background header bar (vs. light blue border)
- "RECOMMENDED" badge replacing the level number
- 2px solid border instead of 1px

## Per-Service Page Layout

Each service's "Setup" tab follows this structure:

```
┌─────────────────────────────────────────────────────────┐
│  [Browse] [SQL] [History] [Stats]  [Setup ▾]           │  ← Mode tabs
├─────────────────────────────────────────────────────────┤
│  🔄 export SPANNER_EMULATOR_HOST="localhost:9010" [Copy]│  ← Quick config strip
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─ Level 1 (Auto-detect) ─────────────────────────┐   │
│  │ ✓ Python · Go · Node.js · Ruby · PHP              │   │
│  │ [Python] [Go] [Node.js] [Java ⚠️]                 │   │  ← Language pills
│  │ ┌──────────────────────────────────────────────┐ │   │
│  │ │ # Python — no code change                    │ │   │  ← Code snippet
│  │ │ from google.cloud import spanner              │ │   │
│  │ │ client = spanner.Client()                     │ │   │
│  │ └──────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  ┌─ Level 2 (Code endpoint) ───────────────────────┐   │
│  │ ⚙️ Java · C#                                     │   │
│  │ [Python] [Go] [Node.js] [Java]                   │   │
│  │ ┌──────────────────────────────────────────────┐ │   │
│  │ │ // Java — conditional channel setup          │ │   │
│  │ │ String host = System.getenv("...");           │ │   │
│  │ │ if (host != null) { channel = ... }           │ │   │
│  │ └──────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  ┌─ Level 3 (DNS proxy) ───────────────────────────┐   │
│  │ Advanced · One-time setup                        │   │
│  │ [Show setup ▸]                                   │   │  ← Collapsed by default
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  ● Auto-detect  ● Code change  ● Infrastructure         │  ← Legend
└─────────────────────────────────────────────────────────┘
```

### Service-specific notice banner

For localcloud-only emulators (BigQuery, Secret Manager, IAM, etc.), an amber warning banner appears above the level cards explaining that SDK auto-detect isn't available:

> ⚠️ **BigQuery SDKs do not auto-detect emulators.** Google's BigQuery client libraries don't read `BIGQUERY_EMULATOR_HOST`. You need either Level 2 (code change) or Level 3 (DNS proxy).

### Language pills

Each level card shows language tabs that filter the code snippet in-place:
- **Active state:** Blue filled pill (`#2563eb` bg, white text)
- **Inactive state:** Gray outline pill (`#f1f5f9` bg, `#64748b` text)
- **⚠️ indicator:** Java pill shows "⚠️" suffix when Level 1 is shown for that language (to indicate "this language needs Level 2 instead")

### Quick config strip

A horizontal bar immediately below the mode tabs showing:
- Service icon + env var export
- Copy button
- Always visible regardless of which level is expanded

### Legend

Bottom strip showing color key (shared across all service setup pages):
- 🟢 Auto-detect (Level 1)
- 🔵 Code change (Level 2)
- ⚫ Infrastructure (Level 3)
- 〇 Not available

## Service Classification

Each service is classified to determine which levels are shown and how:

### Type A: Standard Google emulator (all 3 levels shown, Level 1 is primary)
- Pub/Sub (`PUBSUB_EMULATOR_HOST`)
- Firestore (`FIRESTORE_EMULATOR_HOST`)
- Bigtable (`BIGTABLE_EMULATOR_HOST`)
- Spanner (`SPANNER_EMULATOR_HOST`)
- Cloud Storage (`STORAGE_EMULATOR_HOST`)

### Type B: Localcloud-only emulator (Levels 2 & 3 shown, Level 2 promoted)
- BigQuery (`BIGQUERY_EMULATOR_HOST`)
- Secret Manager (`SECRET_MANAGER_EMULATOR_HOST`)
- Cloud Tasks (`CLOUD_TASKS_EMULATOR_HOST`)
- Cloud KMS (`CLOUD_KMS_EMULATOR_HOST`)
- Cloud Scheduler (`CLOUD_SCHEDULER_EMULATOR_HOST`)
- Cloud Functions (`CLOUD_FUNCTIONS_EMULATOR_HOST`)
- Cloud Logging (`LOGGING_EMULATOR_HOST`)
- Cloud Monitoring (`MONITORING_EMULATOR_HOST`)
- Vertex AI (`AIPLATFORM_EMULATOR_HOST`)
- Cloud SQL (`CLOUD_SQL_EMULATOR_HOST`)
- AlloyDB (`ALLOYDB_EMULATOR_HOST`)
- Cloud IAM (`IAM_EMULATOR_HOST`)
- Dataproc (`DATAPROC_EMULATOR_HOST`)
- GKE (`GKE_EMULATOR_HOST`)
- Cloud Run (`CLOUD_RUN_EMULATOR_HOST`)
- Compute Engine (`COMPUTE_EMULATOR_HOST`)

### Type C: Not an SDK emulator (Setup tab shows protocol-specific guide)
- Memorystore (`REDIS_HOST`) — shows Redis CLI / client library connection instructions (not Google SDK-based)
- Cloud Billing, Service Usage — admin APIs without dedicated client libraries; hide Setup tab

## Content per Level per Language

### Level 1 (Auto-detect) — Code Snippets

For Type A services, show a snippet for each language that demonstrates "it just works":

| Language | Pattern |
|----------|---------|
| **Python** | Import the client, no config changes. Just set the env var. |
| **Go** | Same — `NewClient(ctx, projectID)` auto-detects. |
| **Node.js** | `new PubSub()` auto-detects. |
| **Java** | Shows "⚠️ Java requires Level 2" note instead of Level 1 snippet. |
| **C#** | Shows "⚠️ C# requires Level 2" note instead of Level 1 snippet. |

### Level 2 (Code endpoint) — Code Snippets

Every language gets a conditional snippet showing the pattern:

```python
# Universal pattern — same for every service, different env var
import os
from google.cloud import {service}

endpoint = os.environ.get("{ENV_VAR}")
if endpoint:
    client = {Service}Client(client_options={"api_endpoint": endpoint})
else:
    client = {Service}Client()  # production
```

```go
import "cloud.google.com/go/{service}"
import "google.golang.org/api/option"

var opts []option.ClientOption
if ep := os.Getenv("{ENV_VAR}"); ep != "" {
    opts = append(opts, option.WithEndpoint(ep))
}
client, _ := {service}.NewClient(ctx, projectID, opts...)
```

```java
String host = System.getenv("{ENV_VAR}");
{Service}Settings.Builder builder = {Service}Settings.newBuilder();
if (host != null) {
    ManagedChannel channel = ManagedChannelBuilder
        .forTarget(host).usePlaintext().build();
    builder.setTransportChannelProvider(
        FixedTransportChannelProvider.create(
            GrpcTransportChannel.create(channel)))
        .setCredentialsProvider(NoCredentialsProvider.create());
}
{Service}Client client = {Service}Client.create(builder.build());
```

```javascript
const endpoint = process.env.{ENV_VAR};
const client = endpoint
    ? new {Service}({apiEndpoint: endpoint})
    : new {Service}();
```

### Level 3 (DNS proxy) — Shared content

The DNS proxy instructions are identical across services. They're stored once and rendered with the service-specific path substituted:

```
# /etc/hosts (add these entries)
127.0.0.1  {service}.googleapis.com

# Caddyfile (reverse proxy)
*.googleapis.com {
    tls internal
    @{service} path /{api_path}/*
    handle @{service} { reverse_proxy localhost:{port} }
    handle { reverse_proxy https://googleapis.com }
}
```

Collapsed by default on Type A services (standard emulators), expanded by default on Type B services (as the "zero code" alternative).

## Backend Changes

### No new API endpoints required

All data needed by the Setup tab is either:
- Already in `services.yaml` (env var names, ports, protocols, service metadata)
- Available via `GET /services` (port numbers, env values)
- Static content (code snippets per language, per service, per level)

### New static data file

Create `localcloud-console/src/data/integration-guide.js` containing:
- `SERVICE_TYPE`: maps service ID → "standard" | "custom" | "none"
- `LEVEL_1_LANGUAGES`: `["python", "go", "nodejs", "ruby", "php"]`
- `LEVEL_2_LANGUAGES`: `["python", "go", "nodejs", "java", "csharp"]`
- `LEVEL_1_SNIPPETS`: `{spanner: {python: "...", go: "...", ...}, ...}`
- `LEVEL_2_SNIPPETS`: `{bigquery: {python: "...", go: "...", java: "...", ...}, ...}`
- `LEVEL_3_CONFIG`: `{bigquery: {host: "bigquery.googleapis.com", path: "/bigquery", port: 9050}, ...}`
- `SERVICE_NOTICE`: per-service banner text (e.g., BigQuery's "SDK does not auto-detect")
- `ENV_VAR_MAP`: env var name for each service

### Existing files to modify (frontend)

1. **`ServiceExplorer.jsx`**:
   - Add `"setup"` as a valid mode in `switchPrimaryMode()`
   - Add "Setup" tab to mode tab bar
   - When mode === "setup", render `<ServiceSetupGuide serviceId={...} />`

2. **New component: `ServiceSetupGuide.jsx`**:
   - Fetches service metadata from `integration-guide.js`
   - Renders the 3-level layout
   - Language pill switching with local state
   - Copy-to-clipboard for env vars
   - Collapsible Level 3 section

3. **`settings-data.js`**:
   - May need `SERVICE_META` additions for new services (or use `integration-guide.js` as the single source)

### Existing files to modify (backend)

None. All content is static. No new API endpoints needed.

If we want to support dynamic env var resolution (e.g., `GET /services/spanner/env` returns the current emulator host), that could be added later as a `GET /service/:id/env` endpoint, but it's not needed for v1.

## Console UI Components

### New components

| Component | Purpose |
|-----------|---------|
| `ServiceSetupGuide` | Top-level container. Receives `serviceId` prop. Renders the notice banner + level cards. |
| `LevelCard` | Single integration level. Props: `level`, `title`, `color`, `languages[]`, `snippets{}`, `highlighted`, `collapsed`, `unavailable` |
| `LanguagePills` | Language selector. Props: `languages[]`, `activeLanguage`, `onSelect`. Renders pill buttons. |
| `SetupCodeBlock` | Dark-themed code block with copy button. Reuses existing `CopyableCodeBlock` pattern. |
| `QuickConfigStrip` | Horizontal bar showing env var export with copy action. |
| `SetupNotice` | Warning/notice banner for Type B services. |

### CSS additions

New CSS classes following existing conventions in `components.css`:
- `.setup-guide` — top-level container
- `.setup-level-card` — level card with color-coded left border and header
- `.setup-level-card.unavailable` — strikethrough + dashed + reduced opacity
- `.setup-level-card.recommended` — blue header bar + 2px border
- `.setup-level-header` — header row with badge + title + language list
- `.setup-level-body` — content area with language pills + code block
- `.setup-language-pill` — pill button (active/inactive)
- `.setup-quick-config` — horizontal bar for quick env var copy
- `.setup-legend` — bottom legend strip
- `.setup-notice` — amber warning banner for Type B services

## User Flow

1. User clicks **Spanner** in sidebar → sees Spanner explorer page
2. User clicks **Setup** tab in mode bar
3. Sees Spanner's integration guide: Level 1 (green, expanded, auto-detect), Level 2 (blue, for Java/C#), Level 3 (gray, collapsed)
4. User selects **Python** pill under Level 1 → code snippet updates to Python
5. User clicks **Copy** on the env var strip → `SPANNER_EMULATOR_HOST` is in their clipboard
6. User applies the snippet to their app, refreshes the Spanner explorer, and sees data flowing

## Non-Goals (v1)

- No centralized "Integration Guide" page — every service is self-contained
- No backend API for snippet serving — all snippets are static JS data
- No dynamic env var resolution — hardcoded `localhost:{port}` for code snippets
- No per-language tutorial walkthroughs — just code snippets + env vars

## Decisions on Open Questions

1. **Memorystore (Redis):** Yes, Type C services get a Setup tab with protocol-specific instructions (e.g., `redis-cli -h localhost -p 6379`, language-specific Redis client examples).

2. **Level 3 DNS proxy:** Document-only for v1. localcloud does NOT provide a built-in reverse proxy. Show Caddy and nginx examples.

3. **Non-data services (Pub/Sub, Secret Manager, Cloud Tasks, etc.):** Yes, they get a Setup tab. Their mode bar shows Browse + Setup (no SQL/History/Stats).

4. **Terraform snippets:** Yes, add a "Terraform" language pill to Level 2. Terraform uses `GOOGLE_*_CUSTOM_ENDPOINT` env vars (already in `services.yaml`).
