# Cloud Workflows Emulator -- Technical Design

## Context

LocalCloud runs 14 GCP service emulators inside a single Docker container. Services fall into two categories:

- **In-process facades** run inside the Java Armeria gateway on port 8080. Each extends `AbstractEmulator`, registers a gRPC `BindableService`, and persists state to PostgreSQL via `PostgresDataSource`. Current facades: Secret Manager, Cloud Tasks, Logging, Monitoring, GKE, Compute Engine, Cloud Run, Memorystore.
- **External emulators** run as separate OS processes managed by supervisord. Current externals: GCS (4443), Pub/Sub (8085), Firestore (8086), Bigtable (8087), Spanner (9010), BigQuery (9050).

Cloud Workflows is Google Cloud's serverless orchestration engine. It uses a custom YAML definition format with a proprietary expression language (`${}` syntax), step-based execution, subworkflows, and built-in connectors to other GCP services. The API surface consists of two gRPC services: `google.cloud.workflows.v1` (6 RPCs for workflow CRUD) and `google.cloud.workflows.executions.v1` (4 RPCs for execution lifecycle).

## Goals

- Full API compatibility with `google.cloud.workflows.v1` and `google.cloud.workflows.executions.v1` gRPC services.
- Complete expression evaluator covering arithmetic, comparison, logical operators, JSON path navigation, function calls, and type coercion.
- All step types: assign, call, switch, for, parallel, try/retry/except, raise, return, next, and subworkflow invocation.
- Connector shims routing `googleapis.*` calls to other LocalCloud emulators with auth bypass.
- Callback support for pause-and-resume execution patterns.
- Console integration with workflow list, definition viewer, and execution detail views.
- Seed data support for pre-deploying workflow definitions.

## Non-Goals

- Production-grade parallelism limits (GCP allows thousands of concurrent executions; the emulator targets local dev with modest concurrency).
- 1-year maximum execution duration or durable execution checkpointing across container restarts.
- KMS encryption of workflow source or execution data.
- IAM permission enforcement on workflow or execution operations.
- Full connector coverage for all 200+ GCP APIs (only services LocalCloud already emulates).

## Decision 1: In-Process Facade

Cloud Workflows runs in-process in the Armeria gateway, following the same pattern as Cloud Tasks, Secret Manager, and others.

**Rationale.** Workflows is a stateful service backed by PostgreSQL, not a standalone binary with its own protocol. The execution engine is pure Java logic (YAML parsing, expression evaluation, HTTP calls). There is no upstream open-source emulator to wrap. Running in-process avoids adding another supervisord process, keeps memory usage predictable within the existing `-Xmx512m` JVM budget, and allows direct access to `PostgresDataSource` and the `AbstractEmulator` lifecycle.

**Registration.** A new `WorkflowsEmulator` class extends `AbstractEmulator` and exposes two gRPC `BindableService` instances: `WorkflowsServiceImpl` (workflow CRUD) and `ExecutionsServiceImpl` (execution lifecycle). Both are registered in the Armeria gateway's gRPC service builder alongside existing facades.

**services.yaml entry:**

```yaml
workflows:
  displayName: "Cloud Workflows"
  port: gateway
  protocol: grpc
  envVar: WORKFLOWS_EMULATOR_HOST
  envValuePrefix: ""
  type: facade
  defaultEnabled: true
  gcloudApiName: workflows
```

## Decision 2: PostgreSQL Schema

Two tables, no separate revisions table for MVP.

### `workflows` table

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | Primary key |
| project_id | VARCHAR(128) | GCP project ID |
| location_id | VARCHAR(64) | e.g., `us-central1` |
| name | VARCHAR(256) | Workflow name (unique per project+location) |
| source_contents | TEXT | Raw YAML workflow definition |
| state | VARCHAR(32) | `ACTIVE` or `UNAVAILABLE` |
| service_account | VARCHAR(256) | SA email (stored but not enforced) |
| labels | JSONB | User-defined labels |
| revision_id | VARCHAR(64) | `000001-xxx` format, incremented on update |
| create_time | TIMESTAMPTZ | |
| update_time | TIMESTAMPTZ | |

**Unique constraint:** `(project_id, location_id, name)`.

### `workflow_executions` table

| Column | Type | Notes |
|--------|------|-------|
| id | BIGSERIAL | Primary key |
| workflow_id | BIGINT | FK to `workflows.id` |
| execution_name | VARCHAR(128) | Hex string, unique per workflow |
| state | VARCHAR(32) | `QUEUED`, `ACTIVE`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| argument | JSONB | Input argument passed at creation |
| result | JSONB | Final return value on success |
| error | JSONB | `{message, context, stackTrace}` on failure |
| call_log_level | VARCHAR(32) | `LOG_ALL_CALLS`, `LOG_ERRORS_ONLY`, `LOG_NONE` |
| start_time | TIMESTAMPTZ | When state transitioned to ACTIVE |
| end_time | TIMESTAMPTZ | When state transitioned to terminal |
| step_entries | JSONB | Ordered list of executed step names and statuses (for console display) |

**Why no revisions table.** GCP Workflows tracks revision history for rollback. For local development the current source is sufficient. The `revision_id` column increments on each `UpdateWorkflow` call. A dedicated `workflow_revisions` table can be added later without schema migration issues.

**Why JSONB for step_entries.** Storing step history as a JSONB array in the execution row avoids a join-heavy `workflow_execution_steps` table. For local dev, executions are short-lived and the step count is small (typically under 100 steps). This keeps queries simple and the schema compact.

## Decision 3: Execution Engine Architecture

The execution engine is the core of the emulator. It consists of four components that collaborate to run a workflow definition.

### WorkflowParser

Parses YAML source (via Jackson's YAML module, already a project dependency) into a `WorkflowDefinition` object model:

```
WorkflowDefinition
  mainWorkflow: Workflow
  subworkflows: Map<String, Workflow>

Workflow
  params: List<ParamDef>       // parameter declarations with optional defaults
  steps: List<NamedStep>       // ordered step list

NamedStep
  name: String                 // step label (e.g., "say_hello")
  step: Step                   // polymorphic step type
```

The parser validates structural correctness at parse time: unknown step types, duplicate step names, invalid `next` targets. Expression syntax is NOT validated at parse time -- it is validated lazily at execution time to match GCP behavior.

### Step Types (visitor pattern)

Each step type is a subclass of `Step` with an `execute(ExecutionContext)` method:

| Step Type | Behavior |
|-----------|----------|
| `AssignStep` | Evaluates expressions and assigns results to variables in the current scope |
| `CallStep` | Dispatches to `http.*` stdlib functions, `googleapis.*` connectors, or subworkflows. Stores result in `result` variable. |
| `SwitchStep` | Evaluates conditions in order, jumps to the first matching branch's `next` target |
| `ForStep` | Iterates over a list or range, executing a body of steps for each element |
| `ParallelStep` | Spawns branches on virtual threads, collects results, merges shared variables |
| `TryStep` | Wraps a body in try/retry/except. Retry uses backoff config. Except catches error maps. |
| `RaiseStep` | Throws an error map that propagates up to the nearest TryStep or fails the execution |
| `ReturnStep` | Evaluates an expression and terminates the current workflow/subworkflow with that value |
| `NextStep` | Explicit jump to a named step (replaces default sequential flow) |

### ExecutionContext

Holds all runtime state for a single execution:

- **Variable scopes:** Stack-based. Each subworkflow call pushes a new scope. Variable lookup walks the stack (current scope first). `assign` writes to the current scope.
- **Step pointer:** Current step index within the current workflow's step list.
- **Step history:** Ordered list of `(stepName, status, timestamp)` entries appended after each step completes. Persisted to `step_entries` JSONB column.
- **Execution state:** `QUEUED` / `ACTIVE` / `SUCCEEDED` / `FAILED` / `CANCELLED`. Transitions are atomic (synchronized on the context).
- **Cancellation flag:** Checked between steps. When set, the execution terminates with `CANCELLED` state.

### Async Execution

Executions run on a Java virtual thread pool (`Executors.newVirtualThreadPerTaskExecutor()`, available in Java 21). The lifecycle:

1. `CreateExecution` RPC inserts a row with `QUEUED` state, returns immediately.
2. A virtual thread picks up the execution, transitions to `ACTIVE`, and begins step-by-step evaluation.
3. On completion, the thread updates the row to `SUCCEEDED` (with result) or `FAILED` (with error).
4. `GetExecution` and `ListExecutions` RPCs read directly from PostgreSQL.
5. `CancelExecution` sets the cancellation flag on the `ExecutionContext`. The engine checks this flag between steps and transitions to `CANCELLED`.

**Thread pool sizing.** Virtual threads have negligible overhead. No explicit pool size limit is needed. The practical concurrency limit comes from PostgreSQL connection pool size (HikariCP, already configured in the project).

## Decision 4: Expression Language Implementation

A hand-written recursive descent parser, not ANTLR. This keeps the dependency footprint zero (no generated parser code, no runtime library) and gives full control over error messages.

### Tokenizer

The tokenizer (`ExpressionTokenizer`) scans `${}` content into a flat token list:

| Token Type | Examples |
|------------|----------|
| NUMBER | `42`, `3.14`, `-1` |
| STRING | `"hello"`, `'world'` |
| BOOLEAN | `true`, `false` |
| NULL | `null` |
| IDENTIFIER | `myVar`, `args`, `sys` |
| OPERATOR | `+`, `-`, `*`, `/`, `//`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `and`, `or`, `not`, `in` |
| LPAREN, RPAREN | `(`, `)` |
| LBRACKET, RBRACKET | `[`, `]` |
| DOT | `.` |
| COMMA | `,` |

### Grammar

Precedence from lowest to highest:

```
expression     → logical_or
logical_or     → logical_and ("or" logical_and)*
logical_and    → comparison ("and" comparison)*
comparison     → membership (("==" | "!=" | "<" | ">" | "<=" | ">=") membership)?
membership     → addition ("in" addition)?
addition       → multiplication (("+" | "-") multiplication)*
multiplication → unary (("*" | "/" | "//" | "%") unary)*
unary          → ("not" | "-") unary | postfix
postfix        → primary (call | index | member)*
call           → "(" arguments? ")"
index          → "[" expression "]"
member         → "." IDENTIFIER
primary        → NUMBER | STRING | BOOLEAN | NULL | IDENTIFIER | "(" expression ")" | list_literal | map_literal
list_literal   → "[" (expression ("," expression)*)? "]"
map_literal    → "{" (STRING ":" expression ("," STRING ":" expression)*)? "}"
```

### Type Coercion Rules

Following GCP Workflows behavior:
- String + anything = string concatenation
- Number operations on non-numbers raise a `TypeMismatch` error
- Boolean context: `null`, `0`, `""`, empty list, empty map are falsy; everything else is truthy
- Map/list access on null raises `KeyNotFound` / `IndexOutOfRange`

### Error Handling

Expression evaluation errors produce structured error maps matching GCP format:

```json
{"tag": "TypeMismatch", "message": "Cannot add string and integer"}
```

Unsupported syntax logs a warning and raises a `SyntaxError` tag rather than crashing the JVM.

## Decision 5: Standard Library Implementation

A `StdlibRegistry` maps function names (e.g., `http.get`, `sys.log`, `json.decode`) to Java `Function<List<Object>, Object>` implementations.

### Function Groups

**`http.*`** -- Real HTTP calls using `java.net.http.HttpClient`.

| Function | Behavior |
|----------|----------|
| `http.get(url, headers?, query?, auth?, timeout?)` | GET request, returns `{body, code, headers}` |
| `http.post(url, headers?, body?, query?, auth?, timeout?)` | POST request |
| `http.put(...)`, `http.patch(...)`, `http.delete(...)` | Analogous |

For calls targeting `localhost` or `host.docker.internal`, auth headers are stripped (emulators don't validate auth). Timeout defaults to 30 seconds. Response body is auto-parsed as JSON if `Content-Type` is `application/json`, otherwise returned as a string.

**`sys.*`** -- System functions.

| Function | Behavior |
|----------|----------|
| `sys.get_env(name)` | Reads from `System.getenv()` |
| `sys.log(text, severity?)` | Writes to SLF4J logger at the specified level |
| `sys.now()` | Returns current UTC timestamp as ISO 8601 string |
| `sys.sleep(seconds)` | `Thread.sleep()`. Capped at 300 seconds for local dev. |

**`json.*`** -- JSON serialization via Jackson.

| Function | Behavior |
|----------|----------|
| `json.decode(string)` | Parse JSON string to map/list/primitive |
| `json.encode(value)` | Serialize value to JSON string |
| `json.encode_to_string(value)` | Same as `encode` (GCP alias) |

**`base64.*`** -- Using `java.util.Base64`.

**`math.*`** -- `math.abs`, `math.max`, `math.min`, `math.ceil`, `math.floor`, `math.round`, `math.pow`, `math.log`.

**`text.*`** -- `text.find_all`, `text.find_all_regex`, `text.match_regex`, `text.replace_all`, `text.replace_all_regex`, `text.split`, `text.substring`, `text.to_lower`, `text.to_upper`, `text.url_encode`, `text.url_decode`.

**`list.*`** -- `list.concat`, `list.prepend`, `list.sort`, `list.length`.

**`map.*`** -- `map.get`, `map.keys`, `map.values`, `map.merge`, `map.delete`.

**`int()`, `double()`, `string()`** -- Type casting functions registered at the top level.

### Extensibility

New functions are added by implementing a single-method interface and registering in `StdlibRegistry`. No changes to the parser or evaluator are needed.

## Decision 6: Connector Shims

Connectors translate `googleapis.SERVICE.VERSION.RESOURCE.METHOD` patterns into HTTP calls against LocalCloud emulators.

### ConnectorRegistry

A static registry mapping connector paths to HTTP call templates:

```java
register("googleapis.storage.v1.objects.list",
    "GET", "http://localhost:4443/storage/v1/b/${bucket}/o");
register("googleapis.storage.v1.objects.get",
    "GET", "http://localhost:4443/storage/v1/b/${bucket}/o/${object}");
register("googleapis.bigquery.v2.jobs.query",
    "POST", "http://localhost:9050/bigquery/v2/projects/${projectId}/queries");
register("googleapis.secretmanager.v1.projects.secrets.versions.access",
    "GET", "http://localhost:8080/v1/projects/${project}/secrets/${secret}/versions/${version}:access");
// ... additional mappings
```

### Dispatch Flow

When a `call` step targets a `googleapis.*` connector:

1. Look up the connector path in `ConnectorRegistry`.
2. Substitute path parameters from the call's `args` map into the URL template.
3. Execute the HTTP request using `java.net.http.HttpClient` (same client as `http.*` stdlib).
4. Skip authentication headers -- LocalCloud emulators don't enforce auth.
5. Parse the response and return it to the workflow as a map.

### LRO Handling

Some GCP APIs return long-running operations. For connector calls that would normally return an `Operation`:

- The emulator returns the final result directly (unwrapped from the Operation wrapper).
- No polling loop is needed. This matches the local dev use case where operations complete instantly.

### Unknown Connectors

If a connector path is not in the registry, the emulator logs a warning and attempts a raw HTTP call to the URL constructed from the path pattern. This allows partial functionality for connectors targeting services not yet emulated, provided the user has a real GCP endpoint or mock available.

## Decision 7: Callback Support

Callbacks enable a workflow to pause and wait for an external HTTP signal before continuing.

### Implementation

**`events.create_callback_endpoint()`** is registered as a stdlib function. When called:

1. Generate a unique callback ID (UUID).
2. Register a `CompletableFuture<Map<String, Object>>` in a `CallbackRegistry` keyed by the callback ID.
3. Register a temporary HTTP route on the Armeria gateway: `POST /_localcloud/workflows/callbacks/{callbackId}`.
4. Return `{url: "http://localhost:8080/_localcloud/workflows/callbacks/{callbackId}"}` to the workflow.

**`events.await_callback(callback, timeout?)`** is registered as a stdlib function. When called:

1. Extract the callback ID from the callback map.
2. Block the current virtual thread on the `CompletableFuture.get(timeout, TimeUnit.SECONDS)`.
3. When the future completes (via HTTP POST), return `{http_request: {body: ..., headers: ..., method: "POST"}}`.
4. Timeout defaults to 1800 seconds (30 minutes). On timeout, raise a `TimeoutError`.

**HTTP callback endpoint.** When a POST arrives at `/_localcloud/workflows/callbacks/{id}`:

1. Look up the `CompletableFuture` in `CallbackRegistry`.
2. If found, complete it with the request body and headers. Return `200 OK`.
3. If not found (expired or already consumed), return `404 Not Found`.

**Cleanup.** Callback registrations are removed after completion or timeout. A background sweep removes stale entries every 5 minutes.

## Decision 8: Console Integration

The Solid.js web console gets a new "Workflows" section in the sidebar navigation.

### Pages

**Workflow List** (`/workflows`)
- Table columns: Name, State, Revision ID, Last Updated.
- Row click navigates to workflow detail.
- Data source: `GET /_localcloud/browse/workflows`.

**Workflow Detail** (`/workflows/:name`)
- Two tabs: "Definition" and "Executions".
- Definition tab: YAML source displayed in a read-only CodeMirror editor with YAML syntax highlighting. CodeMirror is already used in the console for the SQL editor -- reuse the same instance/bundle.
- Executions tab: table of executions with State, Start Time, Duration columns. Row click navigates to execution detail.

**Execution Detail** (`/workflows/:name/executions/:id`)
- State badge (color-coded: green=SUCCEEDED, red=FAILED, blue=ACTIVE, gray=QUEUED/CANCELLED).
- Input/Output section: collapsible JSON viewer showing `argument` and `result`.
- Error section (if FAILED): formatted error message and stack trace.
- Step trace: ordered list of executed steps with name, status, and timestamp. Displayed as a vertical timeline.

### Admin API Endpoints

The existing `BrowseService` pattern is extended:

| Endpoint | Returns |
|----------|---------|
| `GET /_localcloud/browse/workflows` | List of workflows for the current project |
| `GET /_localcloud/browse/workflows/{name}` | Workflow detail with source |
| `GET /_localcloud/browse/workflows/{name}/executions` | Execution list for a workflow |
| `GET /_localcloud/browse/workflows/{name}/executions/{id}` | Execution detail with step trace |

These endpoints return JSON for the console to consume. They are separate from the gRPC API and follow the same pattern as existing browse endpoints for other services.

## Decision 9: Seed Data Format

Seed data follows the existing `services:` wrapper pattern used by other LocalCloud services.

```yaml
services:
  workflows:
    workflows:
      - name: "hello-workflow"
        location: "us-central1"
        source: |
          main:
            steps:
              - say_hello:
                  return: "Hello from LocalCloud!"

      - name: "http-workflow"
        location: "us-central1"
        labels:
          env: "dev"
        source: |
          main:
            params: [url]
            steps:
              - make_request:
                  call: http.get
                  args:
                    url: ${url}
                  result: response
              - return_result:
                  return: ${response.body}
```

**Seeding behavior.** The `SeedService` handles the `workflows` section by:

1. Parsing each workflow entry.
2. Inserting directly into the `workflows` PostgreSQL table (same pattern as Secret Manager seeding -- direct DB inserts, not gRPC calls).
3. Setting state to `ACTIVE`, generating an initial `revision_id`.
4. Optionally triggering executions if an `execute` key is present with input arguments.

## Risks and Trade-offs

### Expression Language Edge Cases

The GCP Workflows expression language is not formally specified at the grammar level. The parser is built from documentation examples and observed behavior. The strategy is to implement the core 80% (arithmetic, comparison, JSON access, function calls, string interpolation) and log unsupported syntax with a clear error message rather than crashing. Users can report unsupported patterns, which can be added incrementally.

### Parallel Step Complexity

`ParallelStep` uses Java 21 virtual threads. Each branch runs on its own virtual thread via `StructuredTaskScope`. Shared variable merging follows GCP semantics: each branch operates on a copy of the shared variables, and the `shared` declaration determines which variables are merged back (last-write-wins). Concurrency is capped at 10 branches to match GCP's default.

### Connector Coverage

Only connectors for services LocalCloud already emulates are mapped in `ConnectorRegistry`. This covers GCS, Pub/Sub, Firestore, BigQuery, Secret Manager, Cloud Tasks, Spanner, Bigtable, Logging, and Monitoring. Unknown connector paths fall through to a raw HTTP call with a warning log. Users can work around missing connectors by using `http.*` calls directly.

### Long-Running Executions

There is no durable execution checkpointing. If the container restarts, in-flight executions are lost (their state remains `ACTIVE` in PostgreSQL but no thread is running them). This is acceptable for local development. A future enhancement could add a recovery sweep on startup that marks orphaned `ACTIVE` executions as `FAILED` with a "container restarted" error.

### Memory Impact

The expression evaluator and execution engine are lightweight (no large AST structures, no bytecode generation). The primary memory consumers are execution contexts for concurrent workflows. Each context holds a variable scope stack and step history -- typically under 10 KB. With virtual threads, the per-thread overhead is minimal. The existing `-Xmx512m` JVM setting should accommodate hundreds of concurrent executions without adjustment.

### Expression Parser Correctness

The GCP expression language has undocumented edge cases (operator precedence in nested contexts, string escape sequences, unicode handling). The parser is tested against a suite of expressions extracted from GCP documentation and known workflow patterns. A compatibility test harness can be run against the real GCP Workflows API to verify parity for critical expression patterns.

## Package Structure

```
com.localcloud.emulators.workflows/
  WorkflowsEmulator.java              # AbstractEmulator subclass, lifecycle
  WorkflowsStore.java                 # PostgreSQL CRUD for workflows + executions
  WorkflowsServiceImpl.java           # gRPC service for workflow CRUD (6 RPCs)
  ExecutionsServiceImpl.java          # gRPC service for execution lifecycle (4 RPCs)
  engine/
    WorkflowParser.java               # YAML → WorkflowDefinition
    WorkflowDefinition.java           # Object model (Workflow, NamedStep, Step subtypes)
    ExecutionContext.java             # Runtime state (scopes, step pointer, history)
    ExecutionRunner.java              # Main execution loop (step dispatch, async lifecycle)
    steps/
      Step.java                       # Base interface
      AssignStep.java
      CallStep.java
      SwitchStep.java
      ForStep.java
      ParallelStep.java
      TryStep.java
      RaiseStep.java
      ReturnStep.java
  expression/
    ExpressionTokenizer.java          # String → token list
    ExpressionParser.java             # Token list → AST
    ExpressionEvaluator.java          # AST → value (given an ExecutionContext)
    ExpressionNode.java               # AST node types
  stdlib/
    StdlibRegistry.java               # Function name → implementation registry
    HttpFunctions.java                # http.get, http.post, etc.
    SysFunctions.java                 # sys.log, sys.get_env, etc.
    JsonFunctions.java                # json.decode, json.encode
    Base64Functions.java
    MathFunctions.java
    TextFunctions.java
    ListFunctions.java
    MapFunctions.java
  connectors/
    ConnectorRegistry.java            # Connector path → HTTP template mapping
    ConnectorDispatcher.java          # Executes connector calls
  callbacks/
    CallbackRegistry.java            # Callback ID → CompletableFuture
    CallbackHttpHandler.java         # Armeria HTTP handler for callback endpoint
```

## Test Strategy

Estimated 150-200 new unit tests (JUnit 5 + Mockito), organized by component:

- **ExpressionTokenizer / ExpressionParser / ExpressionEvaluator:** ~60 tests covering literals, operators, precedence, function calls, map/list access, error cases, type coercion.
- **WorkflowParser:** ~20 tests for valid YAML structures, subworkflow parsing, malformed input.
- **Step execution:** ~40 tests (one per step type per scenario: happy path, error, edge case).
- **StdlibRegistry:** ~20 tests for individual stdlib functions.
- **ConnectorDispatcher:** ~10 tests for connector URL templating and dispatch.
- **WorkflowsServiceImpl / ExecutionsServiceImpl:** ~20 tests for gRPC API behavior (CRUD, state transitions, validation).
- **CallbackRegistry:** ~10 tests for registration, completion, timeout, cleanup.
- **WorkflowsStore:** ~10 tests for PostgreSQL operations.

## Dependencies

New Gradle dependencies:

```groovy
implementation 'com.google.api.grpc:proto-google-cloud-workflows-v1:0.x.x'
implementation 'com.google.api.grpc:proto-google-cloud-workflow-executions-v1:0.x.x'
```

No other new dependencies. Jackson YAML, HikariCP, Armeria, SLF4J, and `java.net.http.HttpClient` are already available in the project.
