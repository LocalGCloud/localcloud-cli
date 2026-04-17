# Workflow Environment Variables Spec

## Overview

The workflow env vars system provides a PostgreSQL-backed key-value store for workflow environment variables scoped by project and preset. Variables are injected into the execution context before workflow runs, making them available to `sys.get_env()` and `${}` template resolution. A REST API exposes full CRUD operations.

## ADDED Requirements

### Requirement: CRUD for Environment Variables

The system SHALL provide REST endpoints to create, read, update, and delete workflow environment variables. Variables are scoped by `project_id` and `preset`.

#### Scenario: List all env vars for active preset

WHEN a GET request is sent to `/_localcloud/workflows/env`
THEN the system SHALL return HTTP 200 with a JSON array of all env var rows for the current project's active preset
AND each row SHALL contain `varName`, `varValue`, `preset`, `createdAt`, and `updatedAt`

#### Scenario: List env vars for a specific preset

WHEN a GET request is sent to `/_localcloud/workflows/env?preset=remote`
THEN the system SHALL return HTTP 200 with env var rows filtered to `preset = 'remote'`

#### Scenario: Create a new env var

WHEN a POST request is sent to `/_localcloud/workflows/env` with body `{"varName": "PAYMENT_SERVICE_URL", "varValue": "http://localhost:3001", "preset": "local"}`
THEN the system SHALL insert a row into `workflow_env_vars` with the given values and the current project_id
AND the system SHALL return HTTP 201 with the created row

#### Scenario: Create env var with duplicate name and preset

WHEN a POST request is sent to `/_localcloud/workflows/env` with a `varName` and `preset` combination that already exists
THEN the system SHALL return HTTP 409 with `{"error": "Variable PAYMENT_SERVICE_URL already exists for preset local. Use PUT to update."}`

#### Scenario: Update an existing env var value

WHEN a PUT request is sent to `/_localcloud/workflows/env/{varName}` with body `{"varValue": "http://localhost:4001", "preset": "local"}`
AND the variable exists for the given project and preset
THEN the system SHALL update the `var_value` and `updated_at` fields
AND the system SHALL return HTTP 200 with the updated row

#### Scenario: Update env var that does not exist

WHEN a PUT request is sent to `/_localcloud/workflows/env/{varName}` for a variable that does not exist
THEN the system SHALL return HTTP 404 with `{"error": "Variable {varName} not found for preset {preset}"}`

#### Scenario: Delete an env var

WHEN a DELETE request is sent to `/_localcloud/workflows/env/{varName}?preset=local`
AND the variable exists
THEN the system SHALL delete the row from `workflow_env_vars`
AND the system SHALL return HTTP 204 with no body

#### Scenario: Delete env var that does not exist

WHEN a DELETE request is sent to `/_localcloud/workflows/env/{varName}?preset=local`
AND the variable does not exist
THEN the system SHALL return HTTP 404 with `{"error": "Variable {varName} not found for preset {preset}"}`

---

### Requirement: Resolution Order for sys.get_env()

The system SHALL resolve `sys.get_env(varName)` using the following priority order: (1) `workflow_env_vars` table for the active preset, (2) OS environment variable via `System.getenv()`, (3) `null`.

#### Scenario: Variable exists in env vars table for active preset

WHEN a workflow calls `sys.get_env("PAYMENT_SERVICE_URL")`
AND the `workflow_env_vars` table contains a row with `var_name = 'PAYMENT_SERVICE_URL'` and `preset = {activePreset}`
THEN `sys.get_env()` SHALL return the `var_value` from the table row

#### Scenario: Variable not in table but present as OS env var

WHEN a workflow calls `sys.get_env("SOME_OS_VAR")`
AND no row exists in `workflow_env_vars` for `SOME_OS_VAR` in the active preset
AND `System.getenv("SOME_OS_VAR")` returns a non-null value
THEN `sys.get_env()` SHALL return the OS env var value

#### Scenario: Variable not in table and not in OS env

WHEN a workflow calls `sys.get_env("NONEXISTENT_VAR")`
AND no row exists in `workflow_env_vars` for this variable
AND `System.getenv("NONEXISTENT_VAR")` returns null
THEN `sys.get_env()` SHALL return `null`

#### Scenario: Table value overrides OS env var

WHEN a workflow calls `sys.get_env("PAYMENT_SERVICE_URL")`
AND `workflow_env_vars` contains `var_value = "http://localhost:3001"` for the active preset
AND `System.getenv("PAYMENT_SERVICE_URL")` returns `"http://os-level-value"`
THEN `sys.get_env()` SHALL return `"http://localhost:3001"` (table value wins)

---

### Requirement: Inject Env Vars into ExecutionContext Before Workflow Runs

The system SHALL load all env vars for the active preset and inject them into the `ExecutionContext` as top-level variables before executing a workflow. This enables `${VAR_NAME}` template resolution without any expression evaluator changes.

#### Scenario: Env vars loaded and injected before execution

WHEN `WorkflowsServiceImpl.runExecution()` is called
THEN the system SHALL query `workflow_env_vars` for all rows matching the current project and active preset
AND the system SHALL add each `{varName: varValue}` pair to the initial variable map of the `ExecutionContext`
AND the system SHALL call the expression evaluator with this enriched context

#### Scenario: Workflow template resolves injected env var

WHEN a workflow step contains `url: ${PAYMENT_SERVICE_URL}/api/charge`
AND `PAYMENT_SERVICE_URL` has been injected into the `ExecutionContext` with value `http://localhost:3001`
THEN the expression evaluator SHALL resolve the template to `http://localhost:3001/api/charge`

#### Scenario: Workflow-defined variables take precedence over injected env vars

WHEN a workflow defines a variable `PAYMENT_SERVICE_URL: "hardcoded-value"` in its `main` arguments or assign steps
AND the env vars table also contains `PAYMENT_SERVICE_URL`
THEN the workflow-defined variable SHALL take precedence (env vars are injected before execution, so workflow assignments overwrite them)

---

### Requirement: REST API for Env Vars

The system MUST expose REST endpoints under `/_localcloud/workflows/env` for all CRUD operations. All endpoints SHALL operate on the project derived from the request context.

#### Scenario: GET returns all vars across all presets when no preset filter

WHEN a GET request is sent to `/_localcloud/workflows/env?all=true`
THEN the system SHALL return all rows for the project across all presets, grouped by `varName`
AND each group SHALL contain values for `local`, `remote`, and `production` presets

#### Scenario: Bulk upsert env vars

WHEN a POST request is sent to `/_localcloud/workflows/env/bulk` with a JSON array of `{varName, varValue, preset}` objects
THEN the system SHALL upsert all provided rows using PostgreSQL `ON CONFLICT DO UPDATE`
AND the system SHALL return HTTP 200 with the count of rows upserted
