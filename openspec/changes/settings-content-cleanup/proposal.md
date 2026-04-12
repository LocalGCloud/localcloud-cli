## Why

The Settings page (1463 lines) has significant content duplication and UX issues:

1. **Duplicated content**: The `eval "$(curl ...)"` auto-configure command appears 5 times. Env var lists appear in both the Shell tab and the Help tab. `docker compose up -d` appears twice. Unset commands appear twice.
2. **No copy buttons on Help tab code blocks**: The Shell/CLI/SDK tabs have copy buttons, but the Help & About tab guide content has plain `<GuideCode>` blocks with no copy functionality.
3. **CLI and SDK service sections are always expanded**: With 9 services x 4 languages, the SDK tab is a wall of cards. No way to collapse services you don't need.
4. **Quick Start assumes `docker compose up -d`**: This assumes the user cloned the repo and has docker-compose.yml. Many users will pull the Docker image directly from an artifactory/registry.
5. **No guidance on switching between local and Google Cloud**: The "Revert to GCP" section exists in the Help tab but is disconnected from the Environment tab where users actually set variables.

## What Changes

### Story 1: Deduplicate content across tabs
- Remove all duplicated env var lists, `eval` commands, and `docker compose` instructions from the Help tab
- Help tab should reference the Environment tab for env vars, not duplicate them
- Single source of truth: `autoConfigCmd` constant used everywhere

### Story 2: Add copy buttons to all code blocks
- Every `<GuideCode>` block in the Help tab gets a copy button (reuse existing `CopyIcon`/`CheckIcon` pattern)
- Create a `CopyableCodeBlock` component that wraps code with a copy button

### Story 3: Collapsible/expandable service cards in CLI and SDK tabs
- Each service section in the CLI and SDK tabs is collapsed by default, showing only the service name
- Click to expand and see the code snippet
- "Expand All" / "Collapse All" toggle at the top

### Story 4: Rewrite Quick Start for Docker image users
- Primary path: `docker run` with the published image (no repo clone needed)
- Show: pull image, run container, set env vars, test with a simple SDK call
- Secondary: `docker compose` as an alternative for users with the repo

### Story 5: Add "Local vs Cloud" switching guide to Environment tab
- Below the Quick Setup card, add a concise "Switch to Google Cloud" callout
- Shows the unset command to revert all env vars in one line
- Links to the Help tab for detailed instructions

### Story 6: Extract static data to separate file
- Move `SAMPLE_CODE`, `CLI_COMMANDS`, `SERVICE_META`, `SDK_ORDER` to a new `settings-data.js` file
- Reduces Settings.jsx from ~1463 lines to ~700 lines

## Capabilities

### New Capabilities
- `settings-content-dedup`: Deduplicated content, copyable code blocks, collapsible service sections, Docker-first quick start

### Modified Capabilities

_(none)_

## Impact

- **localcloud-console/src/pages/Settings.jsx**: Major content rewrite — dedup, collapsible sections, new quick start, switching guide
- **localcloud-console/src/pages/settings-data.js**: New file — extracted static data (SAMPLE_CODE, CLI_COMMANDS, SERVICE_META)
- **localcloud-console/src/styles/components.css**: Minor additions for collapsible cards and copy-code-block
- No backend changes
