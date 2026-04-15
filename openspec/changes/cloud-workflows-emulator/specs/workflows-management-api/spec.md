## ADDED Requirements

### Requirement: CreateWorkflow

The emulator SHALL accept a CreateWorkflow gRPC request matching `google.cloud.workflows.v1.Workflows/CreateWorkflow`. The request MUST include a parent resource name in the format `projects/{project}/locations/{location}`, a `workflow_id` string, and a `Workflow` message containing `source_contents` as a YAML string. The emulator MUST store the workflow in PostgreSQL with `state=ACTIVE`, generate a unique `revision_id`, and return a `google.longrunning.Operation` that resolves to the created `Workflow` resource.

#### Scenario: Successful workflow creation returns ACTIVE workflow with revision_id

WHEN a CreateWorkflow request is received with a valid parent, workflow_id, and source_contents YAML string
THEN the emulator MUST persist the workflow to PostgreSQL, set `state=ACTIVE`, assign a non-empty `revision_id`, set `create_time` and `update_time` to the current timestamp, and return a completed `Operation` whose `response` field contains the created `Workflow` resource

#### Scenario: CreateWorkflow with duplicate workflow_id returns ALREADY_EXISTS error

WHEN a CreateWorkflow request is received with a workflow_id that already exists under the same project and location
THEN the emulator MUST return a gRPC status of `ALREADY_EXISTS` and MUST NOT create a duplicate record

#### Scenario: CreateWorkflow with missing source_contents returns INVALID_ARGUMENT

WHEN a CreateWorkflow request is received with an empty or absent `source_contents` field
THEN the emulator MUST return a gRPC status of `INVALID_ARGUMENT`

---

### Requirement: GetWorkflow

The emulator SHALL implement `google.cloud.workflows.v1.Workflows/GetWorkflow`. The request MUST identify the workflow by its full resource name `projects/{project}/locations/{location}/workflows/{workflow_id}`. The emulator MUST return the stored `Workflow` message including all fields. If the workflow does not exist, the emulator MUST return a gRPC status of `NOT_FOUND`.

#### Scenario: Existing workflow is returned by resource name

WHEN a GetWorkflow request is received with a valid resource name matching a stored workflow
THEN the emulator MUST return the `Workflow` message with all persisted fields including `state`, `source_contents`, `revision_id`, `create_time`, and `update_time`

#### Scenario: Non-existent workflow returns NOT_FOUND

WHEN a GetWorkflow request is received with a resource name that does not match any stored workflow
THEN the emulator MUST return a gRPC status of `NOT_FOUND`

---

### Requirement: UpdateWorkflow

The emulator SHALL implement `google.cloud.workflows.v1.Workflows/UpdateWorkflow`. The request MUST include a `Workflow` message with `name` set to an existing resource name and a `update_mask` specifying which fields to update. The emulator MUST update `source_contents` when it is in the mask, increment or generate a new `revision_id`, update `update_time`, and return a `google.longrunning.Operation` that resolves to the updated `Workflow`.

#### Scenario: Updating source_contents increments revision_id

WHEN an UpdateWorkflow request is received with `source_contents` in the update_mask and a new YAML value
THEN the emulator MUST persist the new `source_contents`, generate a new `revision_id` distinct from the previous value, set `update_time` to the current timestamp, and return a completed `Operation` whose `response` contains the updated `Workflow`

#### Scenario: UpdateWorkflow on non-existent workflow returns NOT_FOUND

WHEN an UpdateWorkflow request is received with a resource name that does not match any stored workflow
THEN the emulator MUST return a gRPC status of `NOT_FOUND`

---

### Requirement: DeleteWorkflow

The emulator SHALL implement `google.cloud.workflows.v1.Workflows/DeleteWorkflow`. The emulator MUST soft-delete the workflow record and all associated execution records by marking them with a deleted timestamp rather than removing rows. The emulator MUST return a `google.longrunning.Operation` that completes successfully. Subsequent GetWorkflow requests for the deleted resource MUST return `NOT_FOUND`.

#### Scenario: Deleting a workflow soft-deletes it and its executions

WHEN a DeleteWorkflow request is received with a valid resource name
THEN the emulator MUST mark the workflow and all its executions as deleted in PostgreSQL, return a completed `Operation` with an empty response, and cause subsequent GetWorkflow and GetExecution requests for those resources to return `NOT_FOUND`

#### Scenario: DeleteWorkflow on non-existent workflow returns NOT_FOUND

WHEN a DeleteWorkflow request is received with a resource name that does not match any active workflow
THEN the emulator MUST return a gRPC status of `NOT_FOUND`

---

### Requirement: ListWorkflows

The emulator SHALL implement `google.cloud.workflows.v1.Workflows/ListWorkflows`. The request MUST include a `parent` resource name in the format `projects/{project}/locations/{location}`. The emulator MUST support `page_size` (default 20, max 1000), `page_token` for cursor-based pagination, `filter` as a CEL expression string (stored but minimally evaluated — at minimum `state=ACTIVE` MUST be supported), and `order_by` (default `create_time desc`). The response MUST include a `next_page_token` when additional pages exist.

#### Scenario: ListWorkflows returns paginated results for a project and location

WHEN a ListWorkflows request is received with a valid parent and a page_size of N
THEN the emulator MUST return at most N workflows belonging to that project and location, ordered by the specified or default order, and include a non-empty `next_page_token` if more results exist

#### Scenario: ListWorkflows with next page_token returns the next page

WHEN a ListWorkflows request is received with a `page_token` from a previous response
THEN the emulator MUST return the subsequent page of results that does not overlap with the previous page

#### Scenario: ListWorkflows with filter state=ACTIVE returns only active workflows

WHEN a ListWorkflows request is received with `filter="state=ACTIVE"`
THEN the emulator MUST return only workflows whose `state` field equals `ACTIVE`

---

### Requirement: ListWorkflowRevisions

The emulator SHALL implement `google.cloud.workflows.v1.Workflows/ListWorkflowRevisions`. The request MUST include the workflow resource name. The emulator MUST return the full revision history for that workflow, each entry containing the `revision_id`, `source_contents` at that revision, and `update_time`. Results MUST be ordered by `update_time` descending. The emulator MUST support `page_size` and `page_token`.

#### Scenario: ListWorkflowRevisions returns all revisions in descending order

WHEN a ListWorkflowRevisions request is received for a workflow that has had multiple updates
THEN the emulator MUST return one revision entry per update, ordered with the most recent revision first, each carrying the `revision_id` and `source_contents` from that point in time

#### Scenario: ListWorkflowRevisions on non-existent workflow returns NOT_FOUND

WHEN a ListWorkflowRevisions request is received for a resource name that does not match any active workflow
THEN the emulator MUST return a gRPC status of `NOT_FOUND`

---

### Requirement: Resource Name Format Validation

The emulator SHALL validate all resource name strings in every request. Resource names MUST conform to the pattern `projects/{project}/locations/{location}/workflows/{workflow_id}` for workflow-level operations and `projects/{project}/locations/{location}` for collection-level operations. Any request with a malformed resource name MUST be rejected.

#### Scenario: Malformed parent resource name returns INVALID_ARGUMENT

WHEN any request is received with a `parent` or `name` field that does not match the expected resource name pattern
THEN the emulator MUST return a gRPC status of `INVALID_ARGUMENT` with a message describing the expected format

---

### Requirement: Labels Support

The emulator SHALL persist and return a `labels` map on `Workflow` resources. Labels MUST be stored as a JSONB column in PostgreSQL. The emulator MUST accept labels in CreateWorkflow and UpdateWorkflow requests and return them in GetWorkflow and ListWorkflows responses.

#### Scenario: Labels set on create are returned on get

WHEN a CreateWorkflow request is received with a `labels` map containing one or more key-value pairs
THEN the emulator MUST persist those labels and return the identical map in subsequent GetWorkflow responses

#### Scenario: Labels are updated via UpdateWorkflow with labels in update_mask

WHEN an UpdateWorkflow request is received with `labels` in the `update_mask` and a new labels map
THEN the emulator MUST replace the stored labels with the new map and return the updated labels in subsequent GetWorkflow responses
