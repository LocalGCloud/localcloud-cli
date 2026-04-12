## Context

The Settings page (`Settings.jsx`) currently fetches `/_localcloud/env?format=json` and renders all env vars as a flat `export KEY="VALUE"` code block with a single "Copy to Clipboard" button. The backend (`AdminApiService.java`) builds env vars in order: SDK host vars, project vars, then CLOUDSDK vars — but returns them as a flat map with no grouping metadata.

The console is built with Solid.js, uses CSS variables for theming (light/dark mode), and follows a card-based section layout pattern.

## Goals / Non-Goals

**Goals:**
- Group env vars into 3 clear categories: Common, SDK (per-service), gcloud CLI
- Each env var row has an inline copy icon for one-click copy
- Each category has a "Copy All" button for bulk copy
- Per-service expandable sections with SDK sample code (Python, Node.js, Go, Java) and CLI (`gcloud`) commands
- Responsive layout that works at all console widths
- Consistent with existing design system (cards, code blocks, badges, buttons)

**Non-Goals:**
- Editing env var values from the UI (they're read-only, derived from service config)
- Adding new env vars beyond what the backend already provides
- Generating full application boilerplate (just short, runnable test snippets)

## Decisions

### D1: Frontend-only categorization (no backend API change)

**Choice:** Categorize env vars in the frontend by pattern-matching on variable names. No new backend endpoint format needed.

**Rationale:** The variable naming is deterministic and well-structured:
- `*_EMULATOR_HOST` and `REDIS_HOST` → SDK category, grouped by service
- `GOOGLE_CLOUD_PROJECT`, `GCLOUD_PROJECT` → Common
- `CLOUDSDK_*` → gcloud CLI category

This avoids a backend change for what is purely a presentation concern. The existing `/_localcloud/env?format=json` response contains all the data needed.

**Alternatives considered:**
- *New `?format=categorized` endpoint*: More correct separation of concerns, but adds coupling between frontend layout and backend response shape. Rejected because the categories are stable and naming-convention-based.

### D2: Service-grouped layout with collapsible sections

**Choice:** Each service gets a card that shows its env var(s) with a copy icon, plus an expandable "Sample Code" section with tabbed snippets (Python / Node.js / Go / Java / gcloud CLI).

**Layout:**
```
┌─ Common ──────────────────────────────────────────────────┐
│ GOOGLE_CLOUD_PROJECT    local-project            [copy]   │
│ GCLOUD_PROJECT          local-project            [copy]   │
│                                           [Copy All]      │
└───────────────────────────────────────────────────────────┘

┌─ SDK Environment Variables ───────────────────────────────┐
│ ┌─ Cloud Storage ────────────────────────────────────────┐│
│ │ STORAGE_EMULATOR_HOST   http://localhost:4443  [copy]  ││
│ │ ▸ Sample Code                                          ││
│ └────────────────────────────────────────────────────────┘│
│ ┌─ Pub/Sub ──────────────────────────────────────────────┐│
│ │ PUBSUB_EMULATOR_HOST    localhost:8085         [copy]  ││
│ │ ▸ Sample Code                                          ││
│ └────────────────────────────────────────────────────────┘│
│ ...                                                       │
│                                           [Copy All SDK]  │
└───────────────────────────────────────────────────────────┘

┌─ gcloud CLI Overrides ────────────────────────────────────┐
│ CLOUDSDK_CORE_PROJECT                  local-project [copy]│
│ CLOUDSDK_AUTH_ACCESS_TOKEN    localcloud-dev-token  [copy]│
│ CLOUDSDK_API_ENDPOINT_OVERRIDES_PUBSUB ...          [copy]│
│ ...                                                       │
│                                         [Copy All gcloud] │
└───────────────────────────────────────────────────────────┘
```

**Rationale:** Users typically need either SDK vars (for application code) or gcloud CLI vars (for shell commands), rarely both at once. Separating them reduces cognitive load. Collapsible sample code keeps the page clean by default but available on demand.

### D3: Static sample code snippets (no code generation)

**Choice:** Hardcode sample code snippets per service in a data structure within the component. Each service has snippets for Python, Node.js, Go, Java, and gcloud CLI where applicable.

**Rationale:** The snippets are short (3-8 lines each), stable, and don't depend on runtime state. A static approach is simpler than generating code from templates. Services like Logging/Monitoring that don't have meaningful CLI test commands will show only SDK snippets.

### D4: Env var row as key-value with inline copy

**Choice:** Each env var renders as a row: `KEY` (monospace, muted) + `VALUE` (monospace, bold) + copy icon button. Clicking the copy icon copies `export KEY="VALUE"` to clipboard with brief "Copied!" feedback.

**Rationale:** Users copy env vars to paste into their shell. The `export` prefix is needed for shell use. A per-row copy icon is faster than selecting text or using the bulk copy when only one var is needed.

## Risks / Trade-offs

- **[Risk] Sample code goes stale** → Mitigated by keeping snippets minimal (3-8 lines), testing against actual emulator behavior. Snippets are standard SDK patterns unlikely to change.
- **[Trade-off] Static snippets don't reflect custom ports** → Accepted. Snippets show standard localhost ports. The env var values above each snippet show the actual configured values.
- **[Trade-off] Frontend categorization couples to naming conventions** → Acceptable. The naming conventions (`*_EMULATOR_HOST`, `CLOUDSDK_*`) are GCP standards, unlikely to change.
