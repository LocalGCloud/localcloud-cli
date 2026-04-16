# Remote Workflow Import UI Spec

## Overview

The remote workflow import UI is a modal dialog in the Workflows page that lets developers connect to a remote workflow source, browse available workflows, select workflows to import, and view the results of URL rewriting after import. The UI is built in Solid.js as part of the LocalCloud web UI.

## ADDED Requirements

### Requirement: Import Modal in Workflows Page

The system SHALL display an "Import from Remote" button on the Workflows page. Clicking the button SHALL open a modal dialog. The modal SHALL be dismissible by clicking outside it or pressing Escape.

#### Scenario: Import button is visible on the Workflows page

WHEN the user navigates to the Workflows page in the LocalCloud dashboard
THEN the page SHALL display an "Import from Remote" button near the top of the workflow list

#### Scenario: Clicking the button opens the import modal

WHEN the user clicks "Import from Remote"
THEN a modal dialog SHALL appear with the title "Import from Remote"
AND the modal SHALL initially show the connect form (URL and username inputs)

#### Scenario: Modal closes on dismiss

WHEN the user clicks outside the modal or presses Escape
THEN the modal SHALL close without performing any import action

---

### Requirement: Connect Form (URL + Username)

The modal SHALL display a form with a source URL field and a username field. Submitting the form SHALL call the `POST /_localcloud/capsule/connect` endpoint. On success, the modal SHALL transition to the workflow list view.

#### Scenario: Connect form is shown when no connection exists

WHEN the import modal is opened
AND no remote source connection is currently configured (GET `/_localcloud/capsule/connect` returns `{"connected": false}`)
THEN the modal SHALL display the URL input, username input, and "Connect" button

#### Scenario: Connect form is pre-filled when connection exists

WHEN the import modal is opened
AND a remote source connection is already configured
THEN the modal SHALL pre-fill the URL and username from the stored config
AND the modal SHALL skip to the workflow list view automatically

#### Scenario: Successful connection transitions to workflow list

WHEN the user enters a valid source URL and username and clicks "Connect"
AND the server responds with HTTP 200
THEN the modal SHALL transition to the workflow list view showing available workflows

#### Scenario: Connection failure shows error inline

WHEN the user enters a source URL that cannot be reached and clicks "Connect"
AND the server responds with HTTP 422
THEN the modal SHALL display the error message below the form fields
AND the modal SHALL remain on the connect form view (not transition)

#### Scenario: Empty fields prevent form submission

WHEN the user clicks "Connect" with an empty URL or username field
THEN the modal SHALL display a validation message "URL and username are required"
AND the system SHALL NOT call the API

---

### Requirement: Workflow List with Checkboxes

After successful connection, the modal SHALL display the list of workflows available from the remote source. Each workflow SHALL show its name, step count, and whether it has already been imported. Users SHALL be able to select workflows using checkboxes.

#### Scenario: Workflow list is loaded after connect

WHEN the modal transitions to the workflow list view
THEN the system SHALL call `GET /_localcloud/capsule/workflows`
AND the modal SHALL display each workflow as a row with a checkbox, workflow name, and step count

#### Scenario: Already-imported workflows are shown as disabled

WHEN the workflow list is loaded
AND a workflow has `alreadyImported: true`
THEN its checkbox SHALL be unchecked and disabled
AND the row SHALL display "(already imported)" label next to the workflow name

#### Scenario: Select all / deselect all

WHEN the workflow list has at least one selectable workflow
THEN the modal SHALL display a "Select all" checkbox that selects or deselects all non-imported workflows

#### Scenario: Loading state during workflow list fetch

WHEN the modal is waiting for `GET /_localcloud/capsule/workflows` to respond
THEN the modal SHALL display a loading spinner in place of the workflow list

#### Scenario: Empty workflow list

WHEN `GET /_localcloud/capsule/workflows` returns an empty array
THEN the modal SHALL display the message "No workflows found for user {username} in the remote source."

---

### Requirement: Import Action and Progress

The modal SHALL provide an "Import Selected" button that triggers the import of all selected workflows. Import progress SHALL be displayed inline. After all imports complete, the modal SHALL display a summary.

#### Scenario: Import Selected triggers import for each selected workflow

WHEN the user selects one or more workflows and clicks "Import Selected"
THEN the system SHALL call `POST /_localcloud/capsule/import` for each selected workflow sequentially
AND the modal SHALL display a progress indicator showing how many workflows have been imported out of the total selected

#### Scenario: Per-workflow import progress row

WHEN the import is in progress
THEN each workflow in the selected list SHALL show one of: pending (grey), importing (spinner), success (checkmark), or failed (red X) status

#### Scenario: Import error for one workflow does not block others

WHEN one workflow fails to import (server returns 4xx or 5xx)
THEN the modal SHALL mark that workflow as failed with the error message
AND the system SHALL continue importing the remaining selected workflows

#### Scenario: Import completes and shows summary

WHEN all selected workflows have been processed
THEN the modal SHALL display a summary: "Imported N workflow(s). M failed."
AND the modal SHALL show a "Done" button that closes the modal and refreshes the workflow list

---

### Requirement: Rewriting Preview

After importing a workflow, the modal SHALL show what URL substitutions were made so the user can understand what changed.

#### Scenario: Rewrite results shown per workflow after import

WHEN a workflow is successfully imported
THEN the modal SHALL expand a collapsible section for that workflow showing:
  - each original remote proxy URL that was detected
  - the variable name it was replaced with (e.g., `${PAYMENT_SERVICE_URL}`)
  - the path suffix preserved after the replacement

#### Scenario: No URLs rewritten

WHEN a workflow is imported and no remote proxy URLs were found in its YAML
THEN the rewrite section SHALL display "No URL substitutions made" for that workflow
