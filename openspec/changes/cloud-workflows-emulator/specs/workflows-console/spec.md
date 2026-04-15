## ADDED Requirements

### Requirement: Sidebar navigation

The web console sidebar MUST include a "Workflows" navigation item. The item MUST be positioned between the existing "Service Explorer" and "Usage" items in the sidebar list. Clicking the item MUST navigate to the workflows list page. The item MUST use the same styling conventions as adjacent sidebar items.

#### Scenario: Workflows nav item appears in sidebar

WHEN the user loads the console at `http://localhost:8080`
THEN the sidebar SHALL contain a "Workflows" link
AND the "Workflows" link SHALL appear below "Service Explorer" and above "Usage" in the navigation order

#### Scenario: Clicking Workflows nav item navigates to list page

WHEN the user clicks the "Workflows" sidebar item
THEN the main content area SHALL display the workflow list page
AND the URL SHALL update to `/workflows` (or the console's equivalent client-side route)

---

### Requirement: Workflow list page

The workflow list page MUST display a table of all workflows known to the emulator. The table MUST include the following columns: Name, State, Revision, Last Updated. Each row MUST be clickable and MUST navigate to the workflow detail page for the corresponding workflow. The table MUST display a placeholder message when no workflows exist.

#### Scenario: Workflow list displays table columns

WHEN the user navigates to the workflow list page
AND at least one workflow exists
THEN a table is displayed with columns: Name, State, Revision, Last Updated
AND each row contains data for one workflow

#### Scenario: Clicking a row navigates to detail page

WHEN the user clicks a row in the workflow list table
THEN the console navigates to the detail page for that workflow

#### Scenario: Empty state placeholder shown when no workflows exist

WHEN the user navigates to the workflow list page
AND no workflows have been created
THEN the table area displays a placeholder message indicating no workflows are present

---

### Requirement: Workflow detail page

The workflow detail page MUST display a header containing the workflow name and a state badge. The page MUST contain exactly two tabs: "Definition" and "Executions". The "Definition" tab MUST display the workflow YAML source in a read-only CodeMirror editor with YAML syntax highlighting. The "Executions" tab MUST display a table of all executions for the selected workflow.

#### Scenario: Header shows workflow name and state badge

WHEN the user navigates to a workflow detail page
THEN the header SHALL display the workflow name as a title
AND a state badge SHALL reflect the current workflow state (e.g., ACTIVE, DELETED)

#### Scenario: Definition tab displays YAML source read-only

WHEN the user clicks the "Definition" tab on the workflow detail page
THEN a CodeMirror editor is displayed containing the workflow YAML source
AND the editor MUST be read-only (no text input allowed)
AND YAML syntax highlighting MUST be applied

#### Scenario: Executions tab displays execution table

WHEN the user clicks the "Executions" tab on the workflow detail page
THEN a table of executions for that workflow is displayed
AND each row represents one execution

---

### Requirement: Execution detail view

Each execution entry in the Executions tab MUST display a state badge using color coding: green for SUCCEEDED, red for FAILED, blue for ACTIVE, and gray for CANCELLED. The view MUST display the input argument as formatted JSON, the output result as formatted JSON (when available), an error message and details section when the execution is in FAILED state, and the start and end timestamps. The view MUST handle missing output or error gracefully.

#### Scenario: State badge color reflects execution state

WHEN an execution with state SUCCEEDED is displayed
THEN its state badge MUST be rendered in green

WHEN an execution with state FAILED is displayed
THEN its state badge MUST be rendered in red

WHEN an execution with state ACTIVE is displayed
THEN its state badge MUST be rendered in blue

WHEN an execution with state CANCELLED is displayed
THEN its state badge MUST be rendered in gray

#### Scenario: Input and output JSON are displayed as formatted JSON

WHEN the user views an execution that has completed with a result
THEN the input argument is displayed as pretty-printed JSON
AND the output result is displayed as pretty-printed JSON

#### Scenario: Error details shown for FAILED executions

WHEN the user views an execution in FAILED state
THEN an error section SHALL be visible
AND the error message SHALL be displayed as plain text
AND error tags (if present) SHALL be displayed

#### Scenario: Timestamps are displayed

WHEN the user views any execution
THEN the start timestamp SHALL be displayed in a human-readable format
AND the end timestamp SHALL be displayed if the execution has completed

---

### Requirement: Create Execution

The workflow detail page MUST include a "Create Execution" button visible when the "Executions" tab is active or at the page header level. Clicking the button MUST open a modal dialog. The modal MUST contain a JSON textarea for the execution argument. Submitting the modal MUST call the CreateExecution API for the current workflow. On success the modal MUST close and the executions table MUST refresh.

#### Scenario: Create Execution button opens modal

WHEN the user is on a workflow detail page
AND clicks the "Create Execution" button
THEN a modal dialog opens containing a JSON textarea labeled "Argument (JSON)"

#### Scenario: Submitting modal calls CreateExecution API

WHEN the user enters valid JSON in the argument textarea and clicks "Run"
THEN a POST request is sent to the CreateExecution endpoint for the current workflow
AND the modal closes on success
AND the executions table refreshes to include the new execution

#### Scenario: Invalid JSON in argument textarea shows validation error

WHEN the user enters non-JSON text in the argument textarea and clicks "Run"
THEN the modal SHALL display a validation error message
AND the API SHALL NOT be called

---

### Requirement: Browse API integration

The console MUST fetch workflow data from the `/_localcloud/browse/workflows` endpoint. This endpoint MUST return a JSON response containing the list of workflows in a format consumable by the console. The console MUST use this endpoint as the data source for the workflow list page and for workflow detail data.

#### Scenario: Workflow list page fetches from browse endpoint

WHEN the workflow list page loads
THEN the console sends a GET request to `/_localcloud/browse/workflows`
AND renders the returned workflow data in the table

#### Scenario: Browse endpoint returns structured workflow data

WHEN a GET request is sent to `/_localcloud/browse/workflows`
THEN the response MUST be HTTP 200 with Content-Type `application/json`
AND the response body MUST contain an array of workflow objects each with at minimum: name, state, revisionId, updateTime fields

---

### Requirement: Refresh

When the user is viewing the Executions tab and at least one execution is in ACTIVE state, the console MUST automatically refresh the execution list every 3 seconds. Auto-refresh MUST stop when no executions remain in ACTIVE state or when the user navigates away from the Executions tab.

#### Scenario: Auto-refresh triggers every 3 seconds with active executions

WHEN the user is viewing the Executions tab
AND at least one execution has state ACTIVE
THEN the console SHALL re-fetch the execution list every 3 seconds
AND the table SHALL update to reflect any state changes without a full page reload

#### Scenario: Auto-refresh stops when no active executions remain

WHEN all executions have transitioned out of ACTIVE state
THEN the console SHALL stop the auto-refresh polling

#### Scenario: Auto-refresh stops on tab navigation

WHEN the user switches from the Executions tab to the Definition tab
THEN any active auto-refresh interval MUST be cleared
