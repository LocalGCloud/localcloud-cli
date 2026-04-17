## Why

Teams using a remote GCP Workflow ephemeral environment tool currently must deploy workflows to GCP to test them — even for small changes. This costs money, requires cloud access, and slows iteration. LocalCloud already emulates the GCP Workflows engine locally, but there's no bridge between a remote workflow source and LocalCloud. A developer should be able to import their workflows into LocalCloud, configure where service calls route (local laptop, remote ephemeral env, or production), and iterate on workflow logic in seconds instead of minutes.

## What Changes

- Add a **workflow import connector** backend service that calls the remote source's REST API to discover workflows, templates, deployed services, and proxy URLs.
- Add **workflow import with URL rewriting** — when importing from a remote source, hardcoded proxy URLs (e.g., `http://10.x.x.x/proxy/env/service/path`) are automatically replaced with environment variable patterns (`${SERVICE_NAME_URL}/path`).
- Add a **workflow environment variables** system — a PostgreSQL table storing key-value env vars that are injected into `sys.get_env()` and `${}` template resolution at workflow execution time.
- Add **environment presets** (Local / Remote / Production) — named configurations that bulk-switch all service URLs with one click. The Remote preset is auto-populated from the source API; Local and Production are user-configured.
- Add **web UI** for importing workflows from a remote source and managing environment variables with preset switching.

## Capabilities

### New Capabilities

- `workflow-connector`: Backend service that connects to a remote workflow source via its REST API (`/api/workflows/list`, `/api/workflows/source`, `/api/status/{id}`, `/api/list`). Discovers workflows, templates, deployed services, and their proxy URLs. Stores connection config in PostgreSQL.
- `workflow-url-rewriter`: Parses imported workflow YAML, detects hardcoded remote proxy URLs, extracts service names, and replaces URLs with `${SERVICE_NAME_URL}` environment variable patterns. Generates corresponding env var entries.
- `workflow-env-vars`: PostgreSQL-backed key-value store for workflow environment variables. Variables are injected into the execution context for `sys.get_env()` resolution and `${}` template evaluation. Supports CRUD via REST API.
- `env-var-presets`: Named preset configurations (Local, Remote, Production) that store complete sets of environment variable values. Switching presets bulk-updates all env vars. The Remote preset is auto-populated from the source API's discovered service URLs.
- `workflow-import-ui`: Web UI for connecting to a remote workflow source, browsing available workflows, selecting and importing them. Shows import progress and URL rewriting results.
- `env-vars-ui`: Web UI for managing workflow environment variables — table editor with add/edit/delete, preset selector buttons (Local/Remote/Production), and per-variable override capability.

### Modified Capabilities

- `workflows-execution-engine`: Extend `sys.get_env()` to read from the workflow env vars table (not just OS environment). Extend `${}` template resolution to resolve `${VAR_NAME}` from env vars before expression evaluation.

## Impact

- **localcloud-server**: New Java package `com.localcloud.emulators.workflows.connector` for the workflow import connector. New PostgreSQL tables (`workflow_env_vars`, `workflow_env_presets`). Modify `SysFunctions.java` to read from env vars table. Modify `ExpressionEvaluator.java` to resolve env var patterns in template strings.
- **localcloud-console**: New "Import from Remote" section in Workflows page. New "Environment" tab in Workflows or Settings page with env var editor and preset switcher.
- **services.yaml**: No changes — the workflow import connector is an admin feature, not a GCP service emulator.
- **seed.yaml**: Optionally add default env var presets for LocalCloud's own emulator URLs.
- **Dependencies**: No new external dependencies — the source API is called via Java HttpClient (already used by connectors).
