## ADDED Requirements

### Requirement: events.create_callback_endpoint

The emulator SHALL generate a unique callback URL when `events.create_callback_endpoint` is invoked within a workflow step. The returned map MUST contain a `url` field with value matching the pattern `http://localhost:8080/_localcloud/workflows/callbacks/{callback_id}`. The `callback_id` MUST be a globally unique identifier (UUID v4). The callback endpoint MUST be registered in emulator state immediately upon creation and MUST be associated with the originating execution ID.

#### Scenario: Create callback endpoint returns unique URL

WHEN a workflow step calls `events.create_callback_endpoint` with an http_callback argument
THEN the emulator returns a map containing a `url` field
AND the `url` value matches `http://localhost:8080/_localcloud/workflows/callbacks/{callback_id}` where `callback_id` is a UUID v4 string
AND a second call to `events.create_callback_endpoint` within the same execution returns a different `callback_id`

#### Scenario: Callback endpoint is registered immediately

WHEN `events.create_callback_endpoint` is called
THEN a POST request to the returned URL before `events.await_callback` is called MUST return HTTP 200 and store the body for later retrieval

---

### Requirement: events.await_callback

The emulator SHALL block the current execution step when `events.await_callback` is invoked, suspending progress until either a matching POST request is received at the callback URL or the configured timeout elapses. The default timeout MUST be 30 minutes. When a callback POST is received, the execution MUST resume and the result of `events.await_callback` MUST be the parsed JSON body of the POST request. The execution state MUST be set to `ACTIVE` while waiting.

#### Scenario: Execution resumes with callback body

WHEN a workflow step calls `events.await_callback` with a valid callback endpoint map
AND a POST request with JSON body `{"approved": true}` is sent to the callback URL before the timeout
THEN the execution resumes
AND the result of `events.await_callback` is `{"approved": true}`
AND the execution continues to the next step

#### Scenario: Default timeout is 30 minutes

WHEN a workflow step calls `events.await_callback` without specifying a timeout
THEN the effective timeout applied by the emulator MUST be 1800 seconds (30 minutes)

#### Scenario: Custom timeout is respected

WHEN a workflow step calls `events.await_callback` with `timeout: 60`
THEN the emulator applies a 60-second timeout to that await operation

---

### Requirement: Callback HTTP endpoint

The emulator MUST expose a POST endpoint at `/_localcloud/workflows/callbacks/{callback_id}`. This endpoint MUST accept any valid JSON body. Upon receiving a POST, the emulator MUST resume the execution that is blocked on `events.await_callback` for the matching `callback_id`, delivering the request body as the step result. The endpoint MUST return HTTP 200 with an empty JSON object `{}` on success.

#### Scenario: POST to callback URL resumes execution

WHEN an execution is blocked in `events.await_callback`
AND a POST request with Content-Type `application/json` and body `{"status": "done"}` is sent to the callback URL
THEN the emulator returns HTTP 200 with body `{}`
AND the blocked execution resumes with result `{"status": "done"}`

#### Scenario: Non-JSON body is rejected

WHEN a POST request with Content-Type `text/plain` and non-JSON body is sent to a valid callback URL
THEN the emulator returns HTTP 400

---

### Requirement: Callback expiry

A callback URL MUST be single-use. After a callback is consumed by a POST request, any subsequent POST to the same URL MUST return HTTP 404. A callback URL that has never been consumed but whose associated execution has finished (SUCCEEDED, FAILED, or CANCELLED) MUST also return HTTP 404.

#### Scenario: Second POST to consumed callback returns 404

WHEN a POST has already been sent to a callback URL and the execution has resumed
THEN a second POST to the same callback URL returns HTTP 404

#### Scenario: Callback for finished execution returns 404

WHEN an execution reaches a terminal state (SUCCEEDED, FAILED, or CANCELLED)
AND a POST is sent to a callback URL that was created by that execution but never consumed
THEN the endpoint returns HTTP 404

#### Scenario: Unknown callback_id returns 404

WHEN a POST is sent to `/_localcloud/workflows/callbacks/nonexistent-id`
THEN the emulator returns HTTP 404

---

### Requirement: Timeout behavior

When the timeout for `events.await_callback` elapses before a callback POST is received, the emulator MUST raise a `TimeoutError` in the execution. The execution MUST transition to the FAILED state with an error containing `tags: ["TimeoutError"]`. The callback URL MUST be invalidated upon timeout and return HTTP 404 for any subsequent requests.

#### Scenario: Timeout causes execution to fail with TimeoutError

WHEN a workflow step calls `events.await_callback` with `timeout: 1`
AND no POST is sent to the callback URL within 1 second
THEN the execution transitions to state FAILED
AND the execution error object contains `tags` including `"TimeoutError"`

#### Scenario: Callback URL invalidated after timeout

WHEN the timeout for an `events.await_callback` call elapses
AND a POST is subsequently sent to the expired callback URL
THEN the endpoint returns HTTP 404

---

### Requirement: Multiple callbacks per execution

An execution MAY create multiple callback endpoints across different steps or within the same step. Each callback endpoint MUST have a unique `callback_id`. Each `events.await_callback` call MUST be independently blocking and MUST only resume when its specific callback URL receives a POST. Callbacks from different steps MUST NOT interfere with each other.

#### Scenario: Two sequential callbacks in the same execution

WHEN a workflow execution calls `events.create_callback_endpoint` in step A and `events.await_callback` blocks
AND step A's callback URL receives a POST, resuming execution
AND execution proceeds to step B which calls `events.create_callback_endpoint` again
THEN step B receives a different `callback_id` than step A
AND `events.await_callback` in step B blocks independently until step B's callback URL receives a POST

#### Scenario: Multiple pending callbacks are isolated

WHEN two parallel branches of a workflow each call `events.create_callback_endpoint`
THEN each branch receives a distinct `callback_id`
AND posting to branch A's callback URL resumes only branch A
AND posting to branch B's callback URL resumes only branch B
