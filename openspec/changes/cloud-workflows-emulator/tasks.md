## 1. Project Setup & Dependencies

- [x] 1.1 Add `proto-google-cloud-workflows-v1` and `proto-google-cloud-workflow-executions-v1` gRPC stubs to `build.gradle`
- [x] 1.2 Create Java package `com.localcloud.workflows` with sub-packages: `model`, `engine`, `expression`, `stdlib`, `connector`, `service`
- [x] 1.3 Create PostgreSQL migration: `workflows` table (id, project, location, name, source_contents, state, revision_id, labels JSONB, service_account, call_log_level, create_time, update_time)
- [x] 1.4 Create PostgreSQL migration: `workflow_executions` table (id, workflow_id, state, argument JSONB, result JSONB, error JSONB, start_time, end_time, call_log_level, workflow_revision_id)
- [x] 1.5 Add `workflows` entry to `services.yaml` (port 8080, gRPC, gateway-hosted)

## 2. Expression Evaluator

- [x] 2.1 Implement `ExpressionTokenizer` — tokenize `${}` content into NUMBER, STRING, BOOLEAN, NULL, IDENTIFIER, OPERATOR, LPAREN, RPAREN, LBRACKET, RBRACKET, DOT, COMMA tokens
- [x] 2.2 Implement `ExpressionParser` — recursive descent parser: expression → logical_or → logical_and → comparison → addition → multiplication → unary → postfix → primary
- [x] 2.3 Implement `ExpressionEvaluator` — evaluate parsed AST against an `ExecutionContext` (variable resolution, operator application, function dispatch)
- [x] 2.4 Implement arithmetic operators (+, -, *, /, //, %) with type coercion (string-to-number, number-to-string for concatenation)
- [x] 2.5 Implement comparison operators (==, !=, <, >, <=, >=) and logical operators (and, or, not) with short-circuit evaluation
- [x] 2.6 Implement map access (expr.field, expr["key"]), list access (expr[index]), and chained navigation (a.b.c)
- [x] 2.7 Implement function call dispatch (name(args...)) to stdlib registry
- [x] 2.8 Implement `in` membership operator for lists and maps
- [x] 2.9 Write unit tests for expression evaluator (literals, operators, precedence, access patterns, error cases) — 63 tests

## 3. Standard Library

- [x] 3.1 Implement `StdlibRegistry` — registry mapping function names to Java implementations
- [x] 3.2 Implement `http.*` functions (get, post, put, patch, delete) using Java HttpClient — accept url, headers, body, query, auth, timeout; return {body, code, headers}
- [x] 3.3 Implement `sys.*` functions — get_env (read env vars), log (Java logger), now (UTC timestamp string), sleep (Thread.sleep, capped at 60s)
- [x] 3.4 Implement `json.*` functions — decode (parse JSON string), encode (serialize to JSON string)
- [x] 3.5 Implement `base64.*` functions — encode and decode using java.util.Base64
- [x] 3.6 Implement `math.*` functions — abs, ceil, floor, max, min, round
- [x] 3.7 Implement `text.*` functions — find_all, match_regex, replace_all, split, substring, to_lower, to_upper, url_encode, url_decode
- [x] 3.8 Implement `list.*` functions — concat, prepend, sort
- [x] 3.9 Implement `map.*` functions — get (with default), keys, values, merge
- [x] 3.10 Implement type cast functions — int(), double(), string()
- [x] 3.11 Write unit tests for all stdlib functions — 70 tests

## 4. Execution Engine

- [x] 4.1 Implement `WorkflowParser` — parse YAML into `WorkflowDefinition` (main steps + named subworkflows with params)
- [x] 4.2 Implement `ExecutionContext` — stack-based variable scopes, step history tracking, execution state management
- [x] 4.3 Implement `AssignStep` executor — set variables from evaluated expressions (up to 50 per step)
- [x] 4.4 Implement `CallStep` executor — dispatch to http functions, stdlib, connectors, or subworkflows; store result
- [x] 4.5 Implement `SwitchStep` executor — evaluate conditions in order, execute first match, support default branch
- [x] 4.6 Implement `ForStep` executor — iterate over list or range(start,end), bind value and index variables
- [x] 4.7 Implement `TryRetryExceptStep` executor — catch errors, retry with exponential backoff (initial_delay, max_delay, multiplier, max_retries), bind error to except variable
- [x] 4.8 Implement `RaiseStep` executor — throw error string or map with code/message
- [x] 4.9 Implement `ReturnStep` executor — exit scope with value
- [x] 4.10 Implement `NextStep` executor — jump to named step, error if not found
- [x] 4.11 Implement `ParallelStep` executor — run branches/for-loops on Java virtual threads with concurrency_limit (default 5, max 10)
- [x] 4.12 Implement subworkflow dispatch — param passing, isolated variable scope, return value propagation, max depth 20 enforcement
- [x] 4.13 Implement `WorkflowExecutor` — top-level orchestrator that runs steps sequentially, manages state transitions (QUEUED → ACTIVE → SUCCEEDED/FAILED/CANCELLED)
- [x] 4.14 Write unit tests for execution engine — 19 tests

## 5. Connector Shims

- [x] 5.1 Implement `ConnectorRegistry` — map `googleapis.SERVICE.VERSION.RESOURCE.METHOD` patterns to HTTP URL templates + methods
- [x] 5.2 Register connectors for LocalCloud emulators: storage.v1 (→ :4443), bigquery.v2 (→ :9050), pubsub.v1 (→ :8085), firestore.v1 (→ :8086), secretmanager.v1 (→ :8080), cloudtasks.v2 (→ :8080), spanner.v1 (→ :9010)
- [x] 5.3 Implement connector arg mapping — translate params to URL template variables, query params, JSON body
- [x] 5.4 Implement auth bypass for local emulator calls and LRO unwrapping (return completed result immediately)
- [x] 5.5 Implement unknown connector fallback — log warning, attempt direct HTTP call
- [x] 5.6 Implement HTTP error to Workflows error mapping (HttpError with code/message)
- [x] 5.7 Write unit tests for connector resolution and arg mapping — 12 tests

## 6. Callback Support

- [x] 6.1 Implement `CallbackManager` — register callback endpoints, store `CompletableFuture` keyed by callback ID
- [x] 6.2 Implement `events.create_callback_endpoint` stdlib function — generate UUID callback URL at `/_localcloud/workflows/callbacks/{id}`
- [x] 6.3 Implement `events.await_callback` stdlib function — block execution thread on CompletableFuture with configurable timeout (default 30 min)
- [x] 6.4 Register callback HTTP endpoint in Armeria gateway — accept POST, complete matching future with request body
- [x] 6.5 Implement single-use expiry and timeout cleanup
- [x] 6.6 Write unit tests for callback lifecycle — 8 tests

## 7. Management API (gRPC)

- [x] 7.1 Implement `WorkflowsRepository` — PostgreSQL CRUD for workflows table (HikariCP + Jackson)
- [x] 7.2 Implement `WorkflowsServiceImpl` — CreateWorkflow (validate YAML, store, return Operation), GetWorkflow, UpdateWorkflow (increment revision), DeleteWorkflow (soft-delete)
- [x] 7.3 Implement ListWorkflows — pagination with page_size/page_token, filter by state, order_by support
- [x] 7.4 Implement ListWorkflowRevisions — return revision history ordered by revision_id desc
- [x] 7.5 Implement resource name format validation (`projects/{p}/locations/{l}/workflows/{id}`)
- [x] 7.6 Register `WorkflowsGrpc` service in Armeria gateway alongside existing facades
- [x] 7.7 Write unit tests for management API — 50 tests (WorkflowsServiceImplTest)

## 8. Execution API (gRPC)

- [x] 8.1 Implement `ExecutionsRepository` — PostgreSQL CRUD for workflow_executions table
- [x] 8.2 Implement `ExecutionsServiceImpl` — CreateExecution (validate workflow exists, queue execution, return immediately), GetExecution, CancelExecution
- [x] 8.3 Implement ListExecutions — pagination with page_size/page_token, filter by state
- [x] 8.4 Implement async execution dispatch — submit to ExecutorService (virtual threads), update state QUEUED → ACTIVE → terminal
- [x] 8.5 Register `ExecutionsGrpc` service in Armeria gateway
- [x] 8.6 Write unit tests for execution API — covered in WorkflowsServiceImplTest (50 tests)

## 9. Browse API & Seed Data

- [x] 9.1 Add `/_localcloud/browse/workflows` endpoint — return workflow list with metadata for console
- [x] 9.2 Add `/_localcloud/browse/workflows/{id}/executions` endpoint — return execution list for console
- [x] 9.3 Implement workflows seed handler in `SeedService` — parse `workflows:` section from seed YAML, direct PostgreSQL inserts with UPSERT semantics
- [x] 9.4 Add seed validation — log warning and skip on invalid YAML source, continue with remaining entries
- [x] 9.5 Add sample workflow to `seed.yaml` — a simple hello-world workflow demonstrating basic step types
- [x] 9.6 Write unit tests for seed handler — covered in WorkflowsStoreTest (24 tests)

## 10. Web Console

- [x] 10.1 Add "Workflows" nav item to sidebar in `app.jsx` (between Service Explorer and Usage), add route `#/workflows` and `#/workflows/{id}`
- [x] 10.2 Create `Workflows.jsx` page — workflow list table (Name, State, Revision, Last Updated), clickable rows, empty state
- [x] 10.3 Create `WorkflowDetail.jsx` page — header with name + state badge, two tabs: Definition (CodeMirror YAML viewer, read-only) and Executions (table)
- [x] 10.4 Create execution detail view — state badge (color-coded), input/output JSON viewers, error display, timestamps
- [x] 10.5 Add "Create Execution" button + modal — JSON argument textarea, submit triggers CreateExecution API call
- [x] 10.6 Add auto-refresh for execution list (3s interval while ACTIVE executions exist)
- [x] 10.7 Add workflow SVG icon to `localcloud-console/src/icons/`
- [x] 10.8 Build console: `cd localcloud-console && npm run build`

## 11. Integration & Verification

- [x] 11.1 End-to-end test: deploy a multi-step workflow via seed, execute it, verify SUCCEEDED state with expected result
- [x] 11.2 End-to-end test: workflow calling other LocalCloud emulators via connectors (e.g., create GCS bucket, write BigQuery row)
- [x] 11.3 End-to-end test: workflow with parallel steps, retry logic, and error handling
- [x] 11.4 End-to-end test: callback workflow — create callback, POST to callback URL, verify execution resumes
- [x] 11.5 Verify console renders workflow list, detail, and execution views correctly
- [x] 11.6 Update DEVELOPER_GUIDE.md with Workflows documentation
- [x] 11.7 Update README.md emulated services table to include Workflows (15th service)
