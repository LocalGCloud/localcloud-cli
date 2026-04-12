## Context

Settings.jsx is 1463 lines with 4 tabs (Environment, Cloud & Routing, Preferences, Help & About). The Help tab duplicates content from the Environment tab. CLI/SDK tabs show all 9 services expanded simultaneously. Quick Start assumes `docker compose up -d` which requires the repo.

## Goals / Non-Goals

**Goals:**
- Eliminate all duplicated content (single source of truth per piece of information)
- Every code block gets a copy button
- CLI/SDK service sections collapse/expand individually
- Quick Start works for Docker image users (no repo needed)
- Clear "Local vs Cloud" switching guidance in the Environment tab

**Non-Goals:**
- Changing the 4-tab structure (keeping the tabbed layout from settings-tabbed-layout)
- Changing backend APIs
- Adding new features beyond content cleanup

## Decisions

### D1: CopyableCodeBlock component replaces all raw code blocks

**Choice:** Create a `CopyableCodeBlock` component that wraps any code content with a header (optional label) and copy button. Replace all `<GuideCode>`, `<div class="code-block">`, and inline code blocks with this component.

**Rationale:** Currently there are 3 different code block patterns: `CopyableEnvVar` (for single vars), `EnvTabs` code blocks (with copy headers), and `GuideCode` (no copy button). Unifying into one component ensures consistent UX.

### D2: Help tab references Environment tab instead of duplicating

**Choice:** The Help tab's Quick Start and SDK Setup sections will say "See the Environment tab for the full list" with a clickable link that switches to that tab, instead of repeating the env var lists.

**Rationale:** Single source of truth. When env vars change (new services added), only the Environment tab needs updating.

### D3: Collapsible service cards with "Expand All" toggle

**Choice:** Each service in CLI and SDK tabs renders as a collapsed header (service icon + name). Clicking expands it to show the code snippet. An "Expand All" / "Collapse All" button at the top toggles all.

**Rationale:** 9 services x code blocks = massive scroll. Most users need 1-2 services at a time. Collapse by default, expand on demand.

### D4: Docker-first Quick Start

**Choice:** Quick Start shows `docker run` as the primary startup command:
```
docker run -d -p 8080:8080 -p 4443:4443 ... localcloud/localcloud:latest
```
Then `eval "$(curl ...)"` to set env vars. Then a 3-line Python SDK test. Docker Compose shown as "Alternative" below.

**Rationale:** Developers pulling from artifactory don't have the repo. `docker run` is universal. Docker Compose is for users who cloned the repo.

### D5: Extract static data to settings-data.js

**Choice:** Move `SAMPLE_CODE` (~200 lines), `CLI_COMMANDS` (~60 lines), `SERVICE_META` (~15 lines), `SDK_ORDER` (~5 lines) to `localcloud-console/src/pages/settings-data.js`.

**Rationale:** Reduces Settings.jsx cognitive load. Static data doesn't need to be mixed with component logic.

## Risks / Trade-offs

- **[Trade-off] Help tab becomes thinner** — some content removed (env var lists, docker compose examples). Acceptable because the content lives in the Environment tab one click away.
- **[Trade-off] Collapsed services require an extra click** — users must expand to see code. Mitigated by "Expand All" button and remembering expanded state per session.
