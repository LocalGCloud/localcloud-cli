## Why

LocalCloud emulates 14 GCP services but lacks Cloud Workflows — Google's serverless orchestration platform for chaining service calls, handling retries, and running parallel tasks. Teams building on Workflows today cannot develop or test locally, requiring live GCP access, incurring costs, and slowing iteration. Adding Workflows emulation completes LocalCloud's orchestration story and enables fully offline development of workflow-driven architectures.

## What Changes

- Add a new **Cloud Workflows emulator** as an in-process gRPC facade in the LocalCloud gateway (port 8080), matching the existing architecture for Secret Manager, Cloud Tasks, Logging, etc.
- Implement the **Workflows Management API** (`google.cloud.workflows.v1`) — CRUD for workflow definitions stored in PostgreSQL.
- Implement the **Executions API** (`google.cloud.workflows.executions.v1`) — create, get, list, cancel executions.
- Build a **YAML-based workflow execution engine** supporting all step types: `assign`, `call` (http + connectors), `switch`, `for`, `parallel`, `try`/`retry`/`except`, `raise`, `return`, `next`, subworkflows.
- Build a **custom expression evaluator** for the `${}` syntax — operators, JSON path access, type coercion, and standard library functions (`http.*`, `sys.*`, `json.*`, `base64.*`, `math.*`, `text.*`, `list.*`, `map.*`).
- Implement **connector shims** that route `googleapis.*` connector calls to other LocalCloud emulators (GCS, BigQuery, Pub/Sub, Secret Manager, Firestore, Cloud Tasks, etc.).
- Implement **callback support** (`events.create_callback_endpoint`, `events.await_callback`) for pause-and-resume execution patterns.
- Add **Workflows to the web console** — workflow list, definition viewer, execution history, and execution detail with step-level status.
- Add **seed data support** for Workflows — pre-deploy workflow definitions and optionally trigger executions via `seed.yaml`.
- Add **CLI support** — `localcloud workflows list`, `localcloud workflows execute`, etc.

## Capabilities

### New Capabilities

- `workflows-management-api`: gRPC + REST API for workflow CRUD (create, get, update, delete, list, list revisions). Stores workflow YAML source and metadata in PostgreSQL with revision history.
- `workflows-execution-api`: gRPC + REST API for execution lifecycle (create, get, list, cancel). Manages execution state machine (QUEUED → ACTIVE → SUCCEEDED/FAILED/CANCELLED).
- `workflows-execution-engine`: Core interpreter that parses workflow YAML definitions and executes steps sequentially. Handles all step types (assign, call, switch, for, parallel, try/retry/except, raise, return, next), subworkflow dispatch, and variable scoping.
- `workflows-expression-evaluator`: Custom expression language parser and evaluator for `${}` syntax. Supports arithmetic, comparison, logical, and membership operators; JSON path navigation; string interpolation; and type coercion.
- `workflows-stdlib`: Standard library functions — `http.get/post/put/patch/delete`, `sys.log/get_env/now/sleep`, `json.decode/encode`, `base64.encode/decode`, `math.*`, `text.*`, `list.concat/prepend/sort`, `map.get/keys/values/merge`.
- `workflows-connectors`: Connector shims that translate `googleapis.SERVICE.VERSION.RESOURCE.METHOD` calls into HTTP/gRPC calls to other LocalCloud emulators. Includes automatic auth bypass, LRO polling simulation, and retry behavior.
- `workflows-callbacks`: Callback endpoint registration and execution-pause-resume mechanism via `events.create_callback_endpoint` and `events.await_callback`.
- `workflows-console`: Web console integration — workflow list page, YAML definition viewer with syntax highlighting, execution history table, execution detail view with step-by-step status and variable inspection.
- `workflows-seed`: Seed data support for deploying workflow definitions and optionally triggering executions on container startup via `seed.yaml`.

### Modified Capabilities

_(none — this is a new service addition with no changes to existing service behavior)_

## Impact

- **localcloud-server**: New Java package `com.localcloud.workflows` with gRPC service implementations, PostgreSQL schema (tables: `workflows`, `workflow_revisions`, `workflow_executions`, `workflow_execution_steps`), execution engine, expression evaluator, and connector shim layer.
- **localcloud-server gateway**: Register `WorkflowsGrpc` and `ExecutionsGrpc` services in the Armeria gateway alongside existing facades.
- **localcloud-console**: New "Workflows" page in sidebar navigation, workflow list/detail/execution views.
- **localcloud-cli**: New `workflows` command group.
- **services.yaml**: Add `workflows` service entry (port 8080, gRPC, gateway-hosted).
- **seed.yaml**: Extend seed format with `workflows:` section.
- **Dockerfile/supervisord**: No changes needed — Workflows runs in-process in the gateway.
- **Dependencies**: Add `proto-google-cloud-workflows-v1` and `proto-google-cloud-workflow-executions-v1` gRPC stubs to Gradle. Add a YAML parser for workflow definitions (SnakeYAML already available via Jackson).
- **Test surface**: New JUnit test suite for expression evaluator, step executor, management API, and execution API. Estimated 150–200 new unit tests.
