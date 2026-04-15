## ADDED Requirements

### Requirement: YAML Parsing

The execution engine MUST parse the workflow `source_contents` YAML string into an internal step graph before execution begins. The engine MUST support a `main` workflow entry point and one or more named subworkflows defined at the top level of the YAML document. If the YAML is syntactically invalid or missing a `main` block, the engine MUST fail the execution with a descriptive error before any steps run.

#### Scenario: Valid YAML with main workflow parses successfully

WHEN the engine receives a workflow definition YAML containing a top-level `main` key with a `steps` list
THEN the engine MUST parse the steps into an executable in-memory representation without error and be ready to begin execution at the first step of `main`

#### Scenario: YAML with named subworkflows parses all subworkflows

WHEN the workflow YAML contains additional top-level keys alongside `main`, each with a `steps` list
THEN the engine MUST parse each named subworkflow and register it so it can be dispatched by name from `call` steps

#### Scenario: Invalid YAML fails execution before any step runs

WHEN the workflow `source_contents` contains syntactically invalid YAML
THEN the engine MUST fail the execution with `state=FAILED` and an error message indicating a parse failure, without executing any steps

---

### Requirement: Assign Step

The engine MUST implement the `assign` step type. An `assign` step MUST contain a list of variable assignment expressions. Each assignment MUST set a named variable in the current scope to the result of evaluating an expression. The engine MUST support up to 50 assignments per `assign` step. Expressions MUST support literal values (string, number, boolean, null), variable references, and arithmetic and string operations.

#### Scenario: Assign step sets variables in the current scope

WHEN the engine executes an `assign` step with a list of variable-expression pairs
THEN each named variable MUST be set to the evaluated result of its expression and be accessible to subsequent steps in the same scope

#### Scenario: Assign step with more than 50 assignments fails

WHEN an `assign` step contains more than 50 variable assignments
THEN the engine MUST fail the execution with an error indicating the per-step assignment limit was exceeded

---

### Requirement: Call Step

The engine MUST implement the `call` step type. A `call` step MUST specify a callable target, which MAY be an HTTP function (using `http.get`, `http.post`, `http.put`, `http.delete`, or `http.request`), a standard library function (e.g., `sys.log`, `math.add`), a connector, or the name of a subworkflow defined in the same workflow document. The step MAY specify a `result` variable to store the return value. HTTP calls MUST include a `url` argument and MAY include `headers`, `body`, `auth`, and `timeout`.

#### Scenario: Call step invoking http.get stores response in result variable

WHEN the engine executes a `call` step with `call: http.get`, a valid `url` argument, and a `result` variable name
THEN the engine MUST perform an HTTP GET to the specified URL, store the response object (including `code`, `headers`, and `body`) in the named result variable, and continue to the next step

#### Scenario: Call step invoking a subworkflow dispatches to that subworkflow

WHEN the engine executes a `call` step whose `call` value matches the name of a subworkflow defined in the workflow YAML
THEN the engine MUST dispatch to that subworkflow with any specified `args`, capture its return value in the `result` variable if specified, and resume execution at the step after the `call`

#### Scenario: Call step with unknown target fails execution

WHEN the engine executes a `call` step whose target is neither a recognized stdlib function nor a defined subworkflow name
THEN the engine MUST fail the execution with an error identifying the unknown callable target

---

### Requirement: Switch Step

The engine MUST implement the `switch` step type. A `switch` step MUST contain an ordered list of condition branches. Each branch MUST have a `condition` expression that evaluates to a boolean and a `next` target step name or embedded steps. The engine MUST evaluate conditions in definition order and execute the first branch whose condition is true. A branch without a `condition` field MUST be treated as a default branch and MUST match if no prior condition was true. If no branch matches and no default exists, execution MUST continue to the next step.

#### Scenario: Switch step executes the first matching branch

WHEN the engine executes a `switch` step and the first branch condition evaluates to true
THEN the engine MUST execute that branch and MUST NOT evaluate or execute any subsequent branches

#### Scenario: Switch step executes the default branch when no condition matches

WHEN the engine executes a `switch` step and no branch condition evaluates to true but a default branch (without `condition`) exists
THEN the engine MUST execute the default branch

#### Scenario: Switch step falls through when no branch matches and no default exists

WHEN the engine executes a `switch` step and no branch condition evaluates to true and no default branch is present
THEN the engine MUST continue execution at the step immediately following the `switch` step

---

### Requirement: For Step

The engine MUST implement the `for` step type. A `for` step MUST support iteration over a list variable or a `range(start, end)` expression. The step MUST bind the current element to a named loop variable for each iteration. The step MAY also bind a named index variable to the zero-based iteration index. The engine MUST execute the nested `steps` block for each element in the collection in order.

#### Scenario: For step iterates over each element of a list variable

WHEN the engine executes a `for` step with `in` referencing a list variable
THEN the engine MUST execute the nested steps once per list element, with the loop variable bound to the current element on each iteration, in list order

#### Scenario: For step with range iterates the correct number of times

WHEN the engine executes a `for` step with `range(start, end)` and the difference `end - start` is N
THEN the engine MUST execute the nested steps exactly N times, with the loop variable taking values from `start` (inclusive) to `end` (exclusive)

#### Scenario: For step binds optional index variable correctly

WHEN the engine executes a `for` step that specifies an `index` variable name
THEN the index variable MUST be set to 0 on the first iteration and incremented by 1 on each subsequent iteration

---

### Requirement: Parallel Step

The engine MUST implement the `parallel` step type. A `parallel` step MUST execute its defined branches or a `for` loop concurrently. The engine MUST support a `concurrency_limit` parameter controlling the maximum number of branches or iterations executing at the same time, with a default of 5 and a maximum of 10. The step MUST support a `shared` list of variable names that are accessible for reading and writing across branches; all other variables MUST be scoped to each individual branch. The `parallel` step MUST complete only after all branches or iterations have finished.

#### Scenario: Parallel step executes branches concurrently up to concurrency_limit

WHEN the engine executes a `parallel` step with four branches and `concurrency_limit: 2`
THEN the engine MUST not start more than 2 branches simultaneously and MUST start subsequent branches as running ones complete

#### Scenario: Parallel step with concurrency_limit exceeding 10 uses maximum of 10

WHEN the engine executes a `parallel` step with `concurrency_limit` set to a value greater than 10
THEN the engine MUST cap concurrency at 10

#### Scenario: Parallel step shared variables are visible across branches

WHEN the engine executes a `parallel` step that declares a variable in the `shared` list and multiple branches write to it
THEN the variable MUST be accessible from all branches and writes from any branch MUST be visible to subsequent reads in the same or other branches

---

### Requirement: Try/Retry/Except Step

The engine MUST implement the `try` step type with optional `retry` and `except` blocks. The `try` block MUST execute its nested steps normally. If any step within `try` raises an error, the engine MUST evaluate the `retry` policy before executing the `except` block. The `retry` policy MUST support `initial_delay` (seconds), `max_delay` (seconds), `multiplier` (backoff factor), and `max_retries` (integer). After all retries are exhausted or if no `retry` is specified, the `except` block MUST execute with the error bound to a named variable.

#### Scenario: Try block with no error executes steps and skips except

WHEN the engine executes a `try` step and no error is raised within the nested steps
THEN the engine MUST complete the `try` block normally and MUST NOT execute the `except` block

#### Scenario: Try block error with retry retries up to max_retries times

WHEN the engine executes a `try` step with a `retry` policy and a step within `try` raises an error
THEN the engine MUST re-execute the `try` block up to `max_retries` times with exponential backoff using `initial_delay`, `max_delay`, and `multiplier` before proceeding to the `except` block

#### Scenario: Try block error without retry immediately executes except block

WHEN the engine executes a `try` step with no `retry` policy and a step raises an error
THEN the engine MUST immediately execute the `except` block with the error bound to the declared variable

#### Scenario: Except block receives error as named variable

WHEN an error causes the `except` block to execute
THEN the error MUST be bound to the variable name declared in the `except` clause and be accessible within the `except` steps as a map with at least `code` and `message` fields

---

### Requirement: Raise Step

The engine MUST implement the `raise` step type. A `raise` step MUST throw an error that propagates up the call stack. The raised value MAY be a plain string (used as the error message) or a map containing at minimum a `code` key and a `message` key. If the raise occurs inside a `try` block, the error MUST be caught by that block's `except` handler. If no enclosing `try` block exists, the error MUST fail the execution.

#### Scenario: Raise step inside a try block is caught by the except handler

WHEN the engine executes a `raise` step inside the steps of a `try` block
THEN the error MUST be caught by the enclosing `except` block and execution MUST continue within the `except` block

#### Scenario: Raise step outside any try block fails the execution

WHEN the engine executes a `raise` step that is not enclosed in any `try` block
THEN the engine MUST set the execution state to `FAILED` and populate the execution `error` field with the raised value

---

### Requirement: Return Step

The engine MUST implement the `return` step type. When executed in the `main` workflow, a `return` step MUST exit the workflow with the specified value, set execution `state=SUCCEEDED`, and store the value as the execution `result`. When executed inside a subworkflow, a `return` step MUST exit only that subworkflow and return the value to the calling `call` step.

#### Scenario: Return step in main workflow sets execution to SUCCEEDED with result

WHEN the engine executes a `return` step in the `main` workflow body
THEN the execution MUST transition to `state=SUCCEEDED` and the `result` field MUST contain the JSON-encoded return value

#### Scenario: Return step in subworkflow exits only that subworkflow

WHEN the engine executes a `return` step inside a named subworkflow
THEN execution MUST resume at the step following the `call` step that invoked the subworkflow, and the return value MUST be available in the `result` variable specified by that `call` step

---

### Requirement: Next Step

The engine MUST implement the `next` step type as a goto instruction. A `next` step MUST specify the name of a target step within the same workflow scope. The engine MUST jump to that step and continue execution from there. If the target step name does not exist within the current scope, the engine MUST fail the execution with an error identifying the missing step name.

#### Scenario: Next step jumps to the named target step

WHEN the engine executes a `next` step with a valid target step name
THEN the engine MUST immediately continue execution at the step with that name, skipping any intervening steps

#### Scenario: Next step with unknown target name fails execution

WHEN the engine executes a `next` step whose target name does not match any step in the current scope
THEN the engine MUST fail the execution with an error identifying the unknown target step name

---

### Requirement: Subworkflow Dispatch

The engine MUST support dispatching named subworkflows defined at the top level of the workflow YAML. When a `call` step invokes a subworkflow, the engine MUST create a new variable scope for the subworkflow, binding parameters from the `args` map. The subworkflow MUST NOT inherit or modify variables from the calling scope except through its return value. The engine MUST enforce a maximum call stack depth of 20. If dispatch would exceed this depth, the engine MUST fail the execution with an error.

#### Scenario: Subworkflow executes with isolated variable scope

WHEN the engine dispatches a subworkflow via a `call` step with `args`
THEN the subworkflow MUST execute with a variable scope containing only the provided `args` and MUST NOT be able to read or write the calling workflow's local variables

#### Scenario: Subworkflow return value is available in the calling scope

WHEN a subworkflow executes a `return` step with a value and returns to the caller
THEN the value MUST be stored in the `result` variable declared by the calling `call` step and be accessible to subsequent steps in the caller's scope

#### Scenario: Subworkflow dispatch exceeding max depth 20 fails execution

WHEN a subworkflow dispatch would result in a call stack depth greater than 20
THEN the engine MUST fail the execution with an error indicating the maximum call depth has been exceeded

---

### Requirement: Sequential Execution

The engine MUST execute workflow steps in the order they are defined within a `steps` list unless control flow is altered by a `next`, `return`, `switch`, or error-handling step. Each step MUST complete fully before the next step begins in sequential contexts.

#### Scenario: Steps execute in definition order by default

WHEN the engine executes a workflow with a `steps` list containing steps A, B, and C in that order and no control flow steps are present
THEN step A MUST complete before step B begins, and step B MUST complete before step C begins, in that defined sequence
