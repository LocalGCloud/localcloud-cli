## ADDED Requirements

### Requirement: CreateExecution

The emulator SHALL implement `google.cloud.workflows.executions.v1.Executions/CreateExecution`. The request MUST include a `parent` resource name identifying a deployed workflow in the format `projects/{project}/locations/{location}/workflows/{workflow_id}`. The request MAY include an optional `argument` field containing a JSON string to pass as the workflow input. The emulator MUST create a new execution record with `state=QUEUED`, assign a unique execution ID, set `start_time` to the current timestamp, and return the `Execution` message immediately. The emulator MUST then transition the execution to `ACTIVE` asynchronously before beginning interpretation.

#### Scenario: CreateExecution returns QUEUED execution immediately

WHEN a CreateExecution request is received for an existing workflow with a valid optional JSON argument
THEN the emulator MUST return an `Execution` message with `state=QUEUED`, a non-empty `name` in the format `projects/{p}/locations/{l}/workflows/{w}/executions/{id}`, and a populated `start_time`

#### Scenario: CreateExecution on non-existent workflow returns NOT_FOUND

WHEN a CreateExecution request is received with a parent resource name that does not match any active workflow
THEN the emulator MUST return a gRPC status of `NOT_FOUND`

#### Scenario: CreateExecution with invalid JSON argument returns INVALID_ARGUMENT

WHEN a CreateExecution request is received with an `argument` field that is not valid JSON
THEN the emulator MUST return a gRPC status of `INVALID_ARGUMENT`

#### Scenario: Execution transitions from QUEUED to ACTIVE asynchronously

WHEN a CreateExecution request succeeds and the execution is returned with state=QUEUED
THEN the emulator MUST asynchronously transition the execution to `state=ACTIVE` and begin running the workflow interpreter before returning a terminal state

---

### Requirement: GetExecution

The emulator SHALL implement `google.cloud.workflows.executions.v1.Executions/GetExecution`. The request MUST identify the execution by its full resource name `projects/{project}/locations/{location}/workflows/{workflow_id}/executions/{execution_id}`. The response MUST include `state`, `argument`, `result`, `error`, `start_time`, and `end_time`. For executions in a terminal state, `result` MUST contain the JSON-encoded return value if the workflow succeeded, and `error` MUST be populated if the workflow failed.

#### Scenario: GetExecution returns current state of an in-progress execution

WHEN a GetExecution request is received for an execution currently in state ACTIVE
THEN the emulator MUST return the execution with `state=ACTIVE` and a populated `start_time`; `result` and `error` MUST be absent or empty

#### Scenario: GetExecution returns result for a succeeded execution

WHEN a GetExecution request is received for an execution that has completed with state SUCCEEDED
THEN the emulator MUST return the execution with `state=SUCCEEDED`, a populated `end_time`, and a `result` field containing the JSON-encoded return value of the workflow

#### Scenario: GetExecution returns error for a failed execution

WHEN a GetExecution request is received for an execution that has completed with state FAILED
THEN the emulator MUST return the execution with `state=FAILED`, a populated `end_time`, and an `error` field whose `message`, `code`, and `stack_trace` fields are populated with details from the failure

#### Scenario: GetExecution on soft-deleted execution returns NOT_FOUND

WHEN a GetExecution request is received for an execution whose parent workflow has been deleted
THEN the emulator MUST return a gRPC status of `NOT_FOUND`

---

### Requirement: ListExecutions

The emulator SHALL implement `google.cloud.workflows.executions.v1.Executions/ListExecutions`. The request MUST include a `parent` resource name identifying a workflow. The emulator MUST support `page_size` (default 20, max 1000), `page_token` for cursor-based pagination, and `filter` as a string allowing filtering by `state`. Results MUST be ordered by `start_time` descending by default.

#### Scenario: ListExecutions returns paginated executions for a workflow

WHEN a ListExecutions request is received with a valid parent and page_size of N
THEN the emulator MUST return at most N execution records for that workflow ordered by `start_time` descending, and include a non-empty `next_page_token` if more results exist

#### Scenario: ListExecutions with filter state=ACTIVE returns only active executions

WHEN a ListExecutions request is received with `filter` containing `state=ACTIVE`
THEN the emulator MUST return only executions whose `state` is `ACTIVE`

#### Scenario: ListExecutions with page_token returns non-overlapping subsequent page

WHEN a ListExecutions request is received with a `page_token` obtained from a previous response
THEN the emulator MUST return the next page of results with no overlap with the previous page

---

### Requirement: CancelExecution

The emulator SHALL implement `google.cloud.workflows.executions.v1.Executions/CancelExecution`. The emulator MUST transition an execution from `QUEUED` or `ACTIVE` to `CANCELLED` and set `end_time` to the current timestamp. If the execution is already in a terminal state (`SUCCEEDED`, `FAILED`, or `CANCELLED`), the emulator MUST return a gRPC status of `FAILED_PRECONDITION`.

#### Scenario: Cancelling a QUEUED execution transitions it to CANCELLED

WHEN a CancelExecution request is received for an execution with state=QUEUED
THEN the emulator MUST update the execution to `state=CANCELLED`, set `end_time`, and return the updated `Execution` message

#### Scenario: Cancelling an ACTIVE execution transitions it to CANCELLED

WHEN a CancelExecution request is received for an execution with state=ACTIVE
THEN the emulator MUST stop the running interpreter, update the execution to `state=CANCELLED`, set `end_time`, and return the updated `Execution` message

#### Scenario: Cancelling a terminal execution returns FAILED_PRECONDITION

WHEN a CancelExecution request is received for an execution whose state is SUCCEEDED, FAILED, or CANCELLED
THEN the emulator MUST return a gRPC status of `FAILED_PRECONDITION` and MUST NOT modify the execution record

---

### Requirement: Execution State Machine

The emulator MUST enforce a strict state machine for execution lifecycle. Valid transitions are: `QUEUED` to `ACTIVE`, `ACTIVE` to `SUCCEEDED`, `ACTIVE` to `FAILED`, `ACTIVE` to `CANCELLED`, and `QUEUED` to `CANCELLED`. No backwards transitions are permitted. No transition from a terminal state (`SUCCEEDED`, `FAILED`, `CANCELLED`) to any other state is permitted.

#### Scenario: State transitions follow the defined state machine

WHEN the execution engine processes an execution
THEN the execution MUST progress through states only along the allowed paths: QUEUED -> ACTIVE -> SUCCEEDED | FAILED | CANCELLED, and the emulator MUST reject any attempt to transition from a terminal state

#### Scenario: Execution that reaches SUCCEEDED cannot be transitioned

WHEN an execution has reached state=SUCCEEDED
THEN any internal or external attempt to change its state MUST be ignored or rejected, preserving the terminal state

---

### Requirement: Execution Error Format

When an execution fails, the emulator MUST populate the `error` field on the `Execution` message. The `error` field MUST contain a `code` string, a human-readable `message` string, and a `stack_trace` string that includes the step name and subworkflow context where the failure occurred.

#### Scenario: Failed execution error includes code, message, and stack_trace

WHEN an execution fails due to an unhandled error raised in a workflow step
THEN the emulator MUST set `execution.error.code` to the error code string, `execution.error.message` to the human-readable message, and `execution.error.stack_trace` to a string identifying the step name and any subworkflow call chain where the failure occurred
