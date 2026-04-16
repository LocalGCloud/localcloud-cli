# Environment Variables UI Spec

## Overview

The env vars UI is a section in the Workflows page that provides a table editor for workflow environment variables and a preset selector for bulk switching the active environment configuration. It is built in Solid.js as part of the LocalCloud console.

## ADDED Requirements

### Requirement: Env Vars Editor Table

The system SHALL display a table of workflow environment variables for the currently active preset. The table SHALL support inline editing of variable values and deletion of individual variables.

#### Scenario: Table displays all vars for active preset on load

WHEN the user opens the Workflows page and the Environment section is visible
THEN the system SHALL call `GET /_localcloud/workflows/env`
AND the table SHALL display one row per env var with columns: variable name and value
AND the active preset SHALL be highlighted in the preset selector

#### Scenario: Table is empty when no vars exist

WHEN `GET /_localcloud/workflows/env` returns an empty array
THEN the table SHALL display the message "No environment variables configured for this preset."
AND the "+ Add Variable" button SHALL still be visible

#### Scenario: Inline edit of a variable value

WHEN the user clicks on a value cell in the table
THEN the cell SHALL become an editable text input pre-filled with the current value
AND pressing Enter or clicking away SHALL call `PUT /_localcloud/workflows/env/{varName}` with the new value and current preset
AND the cell SHALL return to read-only display with the updated value

#### Scenario: Edit save failure shows inline error

WHEN a PUT request fails (non-2xx response)
THEN the cell SHALL display the error message inline below the input
AND the original value SHALL be restored in the cell

#### Scenario: Delete a variable

WHEN the user clicks the delete icon (trash) on a variable row
THEN the system SHALL display a confirmation prompt: "Delete {varName} from {preset} preset?"
AND if confirmed, the system SHALL call `DELETE /_localcloud/workflows/env/{varName}?preset={preset}`
AND the row SHALL be removed from the table on success

---

### Requirement: Preset Selector Buttons

The system SHALL display preset selector buttons (Local, Remote, Production, and any custom presets) above the env vars table. Clicking a preset button SHALL switch the active preset and reload the table with that preset's variables.

#### Scenario: Preset buttons are rendered for all known presets

WHEN the Workflows page loads the Environment section
THEN the system SHALL call `GET /_localcloud/workflows/env/presets`
AND the preset selector SHALL display one button per preset name returned
AND the currently active preset button SHALL be visually distinguished (e.g., filled/highlighted style)

#### Scenario: Switching preset reloads env vars table

WHEN the user clicks a preset button (e.g., "Remote")
AND that preset is not already active
THEN the system SHALL call `POST /_localcloud/workflows/env/presets/activate` with `{"preset": "remote"}`
AND on success, the system SHALL call `GET /_localcloud/workflows/env?preset=remote`
AND the table SHALL update to display the Remote preset's variables

#### Scenario: Active preset button is non-clickable or visually distinct

WHEN the user views the preset selector
THEN the currently active preset button SHALL appear in a selected/active visual state
AND clicking the already-active preset button SHALL have no effect (no API call)

#### Scenario: Preset switch failure shows notification

WHEN `POST /_localcloud/workflows/env/presets/activate` returns an error
THEN the system SHALL display an error notification
AND the active preset SHALL remain unchanged

---

### Requirement: Add Variable

The system SHALL provide an "Add Variable" action that lets users create a new env var for the active preset.

#### Scenario: Add Variable opens an inline form or row

WHEN the user clicks "+ Add Variable"
THEN a new editable row SHALL appear at the bottom of the table with empty name and value inputs
AND the cursor SHALL focus the name input

#### Scenario: Save new variable

WHEN the user fills in a variable name and value and presses Enter or clicks a save icon
THEN the system SHALL call `POST /_localcloud/workflows/env` with `{varName, varValue, preset: activePreset}`
AND on success, the new row SHALL appear in the table in read-only display

#### Scenario: Cancel adding a variable

WHEN the user presses Escape or clicks a cancel icon while adding a new variable
THEN the new empty row SHALL be discarded without any API call

#### Scenario: Duplicate variable name within preset

WHEN the user submits a new variable with a name that already exists in the active preset
AND the server returns HTTP 409
THEN the inline form SHALL display "Variable already exists. Edit the existing row to change its value."

---

### Requirement: Show Which Preset Is Active

The system SHALL always make the active preset visible at a glance on the Workflows page.

#### Scenario: Active preset is shown in the section header

WHEN the Environment section of the Workflows page is rendered
THEN the section header SHALL display the active preset name (e.g., "Environment — Local")
AND the corresponding preset button in the selector SHALL appear highlighted

#### Scenario: Active preset indicator updates immediately on switch

WHEN the user switches the active preset to "Production"
THEN the section header SHALL update to "Environment — Production"
AND the Production button SHALL become highlighted
AND the Local button SHALL no longer appear highlighted

#### Scenario: Table shows variable values for active preset only

WHEN the active preset is "local"
THEN the table SHALL display only the `local` preset values for each variable
AND values from `remote` or `production` presets SHALL NOT be shown in the same rows
