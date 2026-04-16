## 1. Database Schema

- [ ] 1.1 Create workflow_env_vars table (project_id, var_name, var_value, preset, timestamps)
- [ ] 1.2 Create workflow_config table for remote source connection settings and active preset
- [ ] 1.3 Add tables to SchemaManager.java

## 2. Workflow Environment Variables

- [ ] 2.1 Create WorkflowEnvVarsRepository — CRUD for env vars (per project, per preset)
- [ ] 2.2 Add REST endpoints: GET/POST/PUT/DELETE /_localcloud/workflows/env
- [ ] 2.3 Add GET /_localcloud/workflows/env/presets — list presets
- [ ] 2.4 Add POST /_localcloud/workflows/env/presets/activate — switch active preset
- [ ] 2.5 Modify SysFunctions.java sys.get_env() — read from env vars table first, fall back to System.getenv()
- [ ] 2.6 Modify WorkflowsServiceImpl.runExecution() — inject env vars into ExecutionContext before execution
- [ ] 2.7 Add default seed env vars for LocalCloud emulator URLs (STORAGE_URL, BIGQUERY_URL, etc.)

## 3. Remote Source Connector

- [ ] 3.1 Create RemoteSourceClient.java — HTTP client for the remote source API (list workflows, get source, list environments, get status)
- [ ] 3.2 Add REST endpoints: POST /_localcloud/capsule/connect, GET /_localcloud/capsule/workflows, GET /_localcloud/capsule/services
- [ ] 3.3 Store remote source connection config (URL, username) in workflow_config table

## 4. Workflow URL Rewriter

- [ ] 4.1 Create WorkflowUrlRewriter.java — scan YAML for remote proxy URL pattern, replace with ${VAR} pattern
- [ ] 4.2 Extract service names from proxy URLs, generate SERVICE_NAME_URL variable names
- [ ] 4.3 Generate env var entries for all three presets (local, remote, production)
- [ ] 4.4 Add POST /_localcloud/capsule/import endpoint — fetch source, rewrite, store workflow + env vars

## 5. Web UI

- [ ] 5.1 Add "Import from Remote" button + modal to Workflows page
- [ ] 5.2 Create RemoteImport component — connect form, workflow list, import action
- [ ] 5.3 Add "Environment" section to Workflows page — env vars table editor
- [ ] 5.4 Add preset selector buttons (Local / Remote / Production) with one-click switching
- [ ] 5.5 Add variable add/edit/delete with inline editing

## 6. Testing & Verification

- [ ] 6.1 Unit tests for WorkflowUrlRewriter (URL detection, rewriting, edge cases)
- [ ] 6.2 Unit tests for env vars resolution (table → OS → null fallback)
- [ ] 6.3 Integration test: import workflow, switch presets, execute, verify routing
- [ ] 6.4 Build web UI and compile server
