## Story 1: Extract static data to settings-data.js

- [x] 1.1 Create `localcloud-console/src/pages/settings-data.js` with exports: `SERVICE_META`, `SDK_ORDER`, `SAMPLE_CODE`, `CLI_COMMANDS`
- [x] 1.2 Update Settings.jsx to import from `./settings-data.js` and remove the inline constants

## Story 2: CopyableCodeBlock component

- [x] 2.1 Create `CopyableCodeBlock` component: renders `<div class="code-block">` with an absolute-positioned copy button in the top-right corner, optional `label` prop shown as a header
- [x] 2.2 Add `.code-block-copyable` CSS: position relative container, absolute copy button top-right, hover visibility
- [x] 2.3 Replace all `<GuideCode>` blocks in the Help tab with `<CopyableCodeBlock>`
- [x] 2.4 Replace all `<Code>` blocks in UserGuideModal (if still used) with `<CopyableCodeBlock>`

## Story 3: Collapsible service cards in CLI and SDK tabs

- [x] 3.1 Add an `expandedServices` signal (Set) to EnvTabs for tracking which services are expanded
- [x] 3.2 Render each service card in CLI/SDK tabs as collapsed by default: show service icon + name + chevron. Click toggles expansion.
- [x] 3.3 Add "Expand All" / "Collapse All" toggle button above the service list in CLI and SDK tabs
- [x] 3.4 Add `.collapsible-card-header` CSS: flex row with icon, name, chevron (rotate on expand), hover highlight, cursor pointer

## Story 4: Docker-first Quick Start

- [x] 4.1 Rewrite Quick Start in Help tab: Step 1 = `docker run` command with common port mappings (`-p 8080:8080 -p 4443:4443 -p 8085:8085 -p 9010:9010`), Step 2 = eval command, Step 3 = 3-line Python test
- [x] 4.2 Add "Using Docker Compose" as a secondary section after the primary docker run path
- [x] 4.3 All code blocks in Quick Start use `CopyableCodeBlock`

## Story 5: Deduplicate Help tab content

- [x] 5.1 Remove the duplicated env var list from the Help tab's SDK Setup section — replace with "See the Environment tab for all variables" with a link that calls `switchTab('environment')`
- [x] 5.2 Remove the duplicated `eval` command from Help tab (it's already in Environment tab Quick Setup)
- [x] 5.3 Remove the duplicated Docker Compose env var example from Help tab SDK section — reference Environment tab
- [x] 5.4 Remove the duplicated `unset` commands from Help tab Revert section — replace with a single `CopyableCodeBlock` containing a one-liner unset-all command
- [x] 5.5 Consolidate the Revert to GCP content into a brief note: "Unset all variables or open a new terminal"

## Story 6: Local vs Cloud switching in Environment tab

- [x] 6.1 Add a compact "Switch to Google Cloud" callout below the Quick Setup card in the Environment tab
- [x] 6.2 Show a one-line unset-all command with copy button: `unset STORAGE_EMULATOR_HOST PUBSUB_EMULATOR_HOST ...`
- [x] 6.3 Style the callout: subtle border, muted text, not visually competing with Quick Setup

## Story 7: Build & Verify

- [x] 7.1 Build console (`cd localcloud-console && npm run build`) — verify no build errors
- [x] 7.2 Verify: no duplicated content across tabs, all code blocks have copy buttons, service cards collapse/expand, Quick Start shows docker run, switching guide visible
