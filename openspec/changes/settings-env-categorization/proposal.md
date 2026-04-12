## Why

The Settings page currently dumps all environment variables as a single flat block of `export` statements. Users must scan ~20+ lines to find the variable relevant to their service. There's no distinction between common project-level vars, SDK-specific emulator host vars, and gcloud CLI endpoint overrides. Additionally, there are no sample code snippets to help users quickly test each service — they must refer to external documentation.

## What Changes

- **Categorized environment variables**: Replace the flat env var block with grouped, collapsible sections:
  - **Common** (required for all): `GOOGLE_CLOUD_PROJECT`, `GCLOUD_PROJECT`
  - **SDK (per-service)**: Emulator host vars like `STORAGE_EMULATOR_HOST`, `PUBSUB_EMULATOR_HOST`, etc. — grouped by service
  - **gcloud CLI**: `CLOUDSDK_CORE_PROJECT`, `CLOUDSDK_AUTH_ACCESS_TOKEN`, and all `CLOUDSDK_API_ENDPOINT_OVERRIDES_*` vars
- **Per-variable copy button**: Each env var row gets an inline copy icon that copies just that variable (not the entire block). "Copy All" remains available per category.
- **Sample code snippets per service**: Each service section includes tabbed SDK (Python/Node.js/Go/Java) and CLI (`gcloud`) sample code to test the service. Snippets are runnable with the env vars set.
- **Backend API enhancement**: New `/_localcloud/env?format=categorized` response format that returns env vars grouped by category, enabling the frontend to render them in sections.

## Capabilities

### New Capabilities
- `categorized-env-display`: Categorized environment variable display with per-variable copy and per-service sample code snippets

### Modified Capabilities

_(none)_

## Impact

- **localcloud-console/src/pages/Settings.jsx**: Major redesign of the Environment Variables section — new categorized layout, copy icons, sample code tabs
- **localcloud-server AdminApiService.java**: New `categorized` format option for `/_localcloud/env` endpoint
- **localcloud-console/src/styles/**: New CSS for env var cards, copy buttons, code snippet tabs
