# Environment Variable Presets Spec

## Overview

Environment variable presets are named configurations (Local, Remote, Production, and user-defined custom presets) that store complete sets of env var values. Switching the active preset bulk-updates which values are used at workflow execution time. The Remote preset is auto-populated from the source API's discovered service URLs during workflow import.

## ADDED Requirements

### Requirement: Named Presets

The system SHALL support at least three built-in preset names: `local`, `remote`, and `production`. Users SHALL be able to create additional custom presets. Preset names SHALL be stored as the `preset` column in `workflow_env_vars` and tracked in the `workflow_config` table.

#### Scenario: List all presets for the current project

WHEN a GET request is sent to `/_localcloud/workflows/env/presets`
THEN the system SHALL return HTTP 200 with a JSON array of preset names derived from distinct `preset` values in `workflow_env_vars` for the project
AND the response SHALL always include `local`, `remote`, and `production` even if they have no variables yet
AND each preset entry SHALL include `name`, `varCount`, and `isActive` (boolean)

#### Scenario: Create a custom preset

WHEN a POST request is sent to `/_localcloud/workflows/env/presets` with body `{"name": "staging"}`
THEN the system SHALL register `staging` as a valid preset name in `workflow_config`
AND subsequent GET requests to `/_localcloud/workflows/env/presets` SHALL include `staging` in the result

#### Scenario: Prevent duplicate preset names

WHEN a POST request is sent to `/_localcloud/workflows/env/presets` with a name that already exists
THEN the system SHALL return HTTP 409 with `{"error": "Preset 'staging' already exists"}`

---

### Requirement: Switch Active Preset

The system SHALL allow switching the active preset with a single API call. Switching the active preset SHALL take effect immediately for all subsequent workflow executions without requiring a server restart.

#### Scenario: Activate a preset

WHEN a POST request is sent to `/_localcloud/workflows/env/presets/activate` with body `{"preset": "remote"}`
THEN the system SHALL update `workflow_config` to set `active_preset = 'remote'` for the current project
AND the system SHALL return HTTP 200 with `{"activePreset": "remote"}`

#### Scenario: Subsequent workflow executions use the newly activated preset

WHEN the active preset is switched to `remote`
AND a workflow execution is started
THEN the system SHALL load env vars with `preset = 'remote'` into the `ExecutionContext`
AND `sys.get_env("PAYMENT_SERVICE_URL")` SHALL return the remote preset value for that variable

#### Scenario: Activate a non-existent preset

WHEN a POST request is sent to `/_localcloud/workflows/env/presets/activate` with `{"preset": "nonexistent"}`
THEN the system SHALL return HTTP 404 with `{"error": "Preset 'nonexistent' does not exist"}`

#### Scenario: Default active preset

WHEN a project has no explicit active preset configured
THEN the system SHALL treat `local` as the default active preset

---

### Requirement: Bulk Update on Preset Switch

When the active preset changes, all subsequent `sys.get_env()` calls SHALL read from the new preset's values without any intermediate state.

#### Scenario: No data migration on preset switch

WHEN the active preset is switched from `local` to `remote`
THEN the system SHALL NOT copy or merge variable values between presets
AND each preset's values SHALL remain independent and unchanged
AND only the `active_preset` pointer in `workflow_config` is updated

---

### Requirement: Auto-Populate Remote Preset from API Discovery

The system SHALL automatically populate the `remote` preset with service URLs discovered from the source API when workflows are imported. This occurs as part of the import flow, not as a separate operation.

#### Scenario: Remote preset values are set during workflow import

WHEN a workflow is imported via `POST /_localcloud/workflow/import`
AND the URL rewriter detects remote proxy URLs for services
THEN the system SHALL upsert rows in `workflow_env_vars` with `preset = 'remote'` and `var_value = {proxyBaseUrl}` for each discovered service
AND existing `remote` preset values for the same `varName` SHALL be overwritten with the newly discovered URL

#### Scenario: Remote preset values are not overwritten for non-imported services

WHEN a workflow import discovers `payment-service` but the `remote` preset already has a value for `ORDER_SERVICE_URL`
THEN the system SHALL only update `PAYMENT_SERVICE_URL` in the `remote` preset
AND `ORDER_SERVICE_URL` in the `remote` preset SHALL remain unchanged

#### Scenario: Re-importing a workflow refreshes the remote preset URL

WHEN a workflow that was previously imported is imported again
AND the proxy URL for a service has changed (e.g., new IP)
THEN the system SHALL overwrite the `remote` preset value for the affected variable with the new proxy URL

---

### Requirement: Active Preset Tracking

The system SHALL persist the active preset selection so it survives server restarts. The `active_preset` value SHALL be stored in the `workflow_config` PostgreSQL table.

#### Scenario: Active preset persists across restarts

WHEN the active preset is switched to `production`
AND the LocalCloud server is restarted
THEN the active preset SHALL still be `production` after restart
AND workflow executions SHALL use the `production` preset values

#### Scenario: Retrieve current active preset

WHEN a GET request is sent to `/_localcloud/workflows/env/presets`
THEN the response SHALL include an `activePreset` field at the top level identifying the currently active preset name
