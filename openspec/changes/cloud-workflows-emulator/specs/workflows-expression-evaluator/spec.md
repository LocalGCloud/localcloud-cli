## ADDED Requirements

### Requirement: Expression Delimiter Parsing

The evaluator SHALL parse expressions enclosed within `${` and `}` delimiters. Expressions MUST NOT exceed 400 characters in length. The parser MUST return an error when the closing `}` delimiter is absent or when the expression length exceeds the 400-character limit.

#### Scenario: Parse valid expression within delimiters

WHEN an expression string `${"hello" + " world"}` is submitted to the evaluator
THEN the evaluator SHALL extract `"hello" + " world"` as the inner expression and return the string `hello world`

#### Scenario: Reject expression exceeding maximum length

WHEN an expression is submitted where the content between `${` and `}` exceeds 400 characters
THEN the evaluator SHALL return an error indicating the expression length limit has been exceeded and SHALL NOT evaluate the expression

#### Scenario: Reject expression with missing closing delimiter

WHEN an expression string `${"unclosed` is submitted to the evaluator
THEN the evaluator SHALL return a parse error indicating a missing `}` delimiter

---

### Requirement: Arithmetic Operators

The evaluator SHALL support the arithmetic operators `+`, `-`, `*`, `/`, `//` (integer division), and `%` (modulo). Division by zero MUST produce an error. The `/` operator SHALL return a float result. The `//` operator SHALL return an integer result by truncating toward zero.

#### Scenario: Evaluate addition of two integers

WHEN the expression `${3 + 4}` is evaluated
THEN the evaluator SHALL return the integer `7`

#### Scenario: Evaluate integer division

WHEN the expression `${7 // 2}` is evaluated
THEN the evaluator SHALL return the integer `3`

#### Scenario: Evaluate modulo

WHEN the expression `${10 % 3}` is evaluated
THEN the evaluator SHALL return the integer `1`

#### Scenario: Error on division by zero

WHEN the expression `${5 / 0}` is evaluated
THEN the evaluator SHALL return an error with a message indicating division by zero

---

### Requirement: Comparison Operators

The evaluator SHALL support the comparison operators `==`, `!=`, `<`, `>`, `<=`, and `>=`. These operators MUST return a boolean value. Comparisons between incompatible types MUST produce a type mismatch error.

#### Scenario: Evaluate equality comparison returning true

WHEN the expression `${5 == 5}` is evaluated
THEN the evaluator SHALL return the boolean `true`

#### Scenario: Evaluate less-than comparison returning false

WHEN the expression `${10 < 3}` is evaluated
THEN the evaluator SHALL return the boolean `false`

#### Scenario: Error on incompatible type comparison

WHEN the expression `${"hello" > 5}` is evaluated
THEN the evaluator SHALL return a type mismatch error

---

### Requirement: Logical Operators with Short-Circuit Evaluation

The evaluator SHALL support the logical operators `and`, `or`, and `not`. Short-circuit evaluation MUST be applied: `and` SHALL NOT evaluate its right operand when the left operand is `false`, and `or` SHALL NOT evaluate its right operand when the left operand is `true`. The `not` operator MUST return the boolean negation of its operand.

#### Scenario: Short-circuit evaluation of `and`

WHEN the expression `${false and undefined_var}` is evaluated and `undefined_var` is not defined in the context
THEN the evaluator SHALL return `false` without raising an undefined variable error for `undefined_var`

#### Scenario: Short-circuit evaluation of `or`

WHEN the expression `${true or undefined_var}` is evaluated and `undefined_var` is not defined in the context
THEN the evaluator SHALL return `true` without raising an undefined variable error for `undefined_var`

#### Scenario: Evaluate `not` operator

WHEN the expression `${not true}` is evaluated
THEN the evaluator SHALL return the boolean `false`

---

### Requirement: Membership Operator

The evaluator SHALL support the `in` operator for testing membership in lists and maps. For lists, `in` MUST return `true` if the left operand is an element of the list. For maps, `in` MUST return `true` if the left operand is a key in the map.

#### Scenario: Test membership in a list

WHEN the expression `${3 in [1, 2, 3]}` is evaluated
THEN the evaluator SHALL return the boolean `true`

#### Scenario: Test key membership in a map

WHEN the expression `${"name" in {"name": "alice"}}` is evaluated
THEN the evaluator SHALL return the boolean `true`

#### Scenario: Test non-membership in a list

WHEN the expression `${5 in [1, 2, 3]}` is evaluated
THEN the evaluator SHALL return the boolean `false`

---

### Requirement: Map Field and Key Access

The evaluator SHALL support map field access using dot notation (`expr.field`) and bracket notation (`expr["key"]`). Nested access chains such as `a.b.c` MUST be supported. Accessing a key that does not exist in the map MUST produce a key-not-found error.

#### Scenario: Access map field via dot notation

WHEN the execution context contains `{"user": {"name": "alice"}}` and the expression `${user.name}` is evaluated
THEN the evaluator SHALL return the string `alice`

#### Scenario: Access map field via bracket notation

WHEN the execution context contains `{"config": {"timeout": 30}}` and the expression `${config["timeout"]}` is evaluated
THEN the evaluator SHALL return the integer `30`

#### Scenario: Error on missing map key

WHEN the execution context contains `{"data": {}}` and the expression `${data.missing_key}` is evaluated
THEN the evaluator SHALL return a key-not-found error

---

### Requirement: List Index Access

The evaluator SHALL support list element access using bracket notation (`expr[index]`). Index values MUST be non-negative integers. Negative indices MUST produce an error. Out-of-bounds indices MUST produce an index-out-of-bounds error.

#### Scenario: Access list element at valid index

WHEN the execution context contains `{"items": ["a", "b", "c"]}` and the expression `${items[1]}` is evaluated
THEN the evaluator SHALL return the string `b`

#### Scenario: Error on negative index

WHEN the expression `${items[-1]}` is evaluated
THEN the evaluator SHALL return an error indicating that negative indices are not supported

#### Scenario: Error on out-of-bounds index

WHEN the execution context contains `{"items": ["a"]}` and the expression `${items[5]}` is evaluated
THEN the evaluator SHALL return an index-out-of-bounds error

---

### Requirement: Function Call Dispatch

The evaluator SHALL support function call syntax of the form `name(arg1, arg2, ...)`. Function names MUST be dispatched against a registered stdlib registry. Calls to functions not present in the registry MUST produce an unknown function error.

#### Scenario: Dispatch registered function call

WHEN the expression `${text.to_upper("hello")}` is evaluated and `text.to_upper` is present in the stdlib registry
THEN the evaluator SHALL invoke the function and return the string `HELLO`

#### Scenario: Error on unknown function

WHEN the expression `${nonexistent.func("arg")}` is evaluated and `nonexistent.func` is not registered
THEN the evaluator SHALL return an unknown function error

---

### Requirement: String Concatenation with `+` Operator

The evaluator SHALL support string concatenation using the `+` operator when both operands are strings or when a number is coerced to a string during concatenation.

#### Scenario: Concatenate two string literals

WHEN the expression `${"foo" + "bar"}` is evaluated
THEN the evaluator SHALL return the string `foobar`

#### Scenario: Coerce number to string in concatenation

WHEN the expression `${"count: " + 42}` is evaluated
THEN the evaluator SHALL return the string `count: 42`

---

### Requirement: Type Coercion

The evaluator SHALL coerce numbers to strings when used with the `+` operator alongside a string operand. The evaluator SHALL coerce strings to numbers when used in arithmetic operations. If a string cannot be parsed as a valid number during arithmetic coercion, the evaluator MUST return a type mismatch error.

#### Scenario: Coerce valid numeric string to number in arithmetic

WHEN the expression `${"3" * 4}` is evaluated
THEN the evaluator SHALL return the integer `12`

#### Scenario: Error on invalid string-to-number coercion

WHEN the expression `${"abc" * 2}` is evaluated
THEN the evaluator SHALL return a type mismatch error indicating the string cannot be coerced to a number

---

### Requirement: Literal Values

The evaluator SHALL support the following literal types: integers, floats, single-quoted strings, double-quoted strings, the boolean values `true` and `false`, and the null value `null`.

#### Scenario: Evaluate integer literal

WHEN the expression `${42}` is evaluated
THEN the evaluator SHALL return the integer `42`

#### Scenario: Evaluate float literal

WHEN the expression `${3.14}` is evaluated
THEN the evaluator SHALL return the float `3.14`

#### Scenario: Evaluate null literal

WHEN the expression `${null}` is evaluated
THEN the evaluator SHALL return the null value

#### Scenario: Evaluate boolean literal

WHEN the expression `${true}` is evaluated
THEN the evaluator SHALL return the boolean `true`

---

### Requirement: Variable References from Execution Context

The evaluator SHALL resolve bare identifiers as variable references against the current execution context. Referencing a variable not present in the execution context MUST produce an undefined variable error.

#### Scenario: Resolve defined variable from context

WHEN the execution context contains `{"x": 10}` and the expression `${x + 5}` is evaluated
THEN the evaluator SHALL return the integer `15`

#### Scenario: Error on undefined variable reference

WHEN the execution context does not contain the variable `y` and the expression `${y}` is evaluated
THEN the evaluator SHALL return an undefined variable error

---

### Requirement: Operator Precedence

The evaluator SHALL apply the following operator precedence from highest to lowest: unary operators (`not`, unary `-`), then multiplicative operators (`*`, `/`, `//`, `%`), then additive operators (`+`, `-`), then comparison operators (`==`, `!=`, `<`, `>`, `<=`, `>=`), then `not`, then `and`, then `or`. Parentheses MUST override default precedence.

#### Scenario: Multiplicative before additive precedence

WHEN the expression `${2 + 3 * 4}` is evaluated
THEN the evaluator SHALL return the integer `14` (not `20`)

#### Scenario: `and` before `or` precedence

WHEN the expression `${false or true and true}` is evaluated
THEN the evaluator SHALL return the boolean `true` (evaluated as `false or (true and true)`)

#### Scenario: Parentheses override precedence

WHEN the expression `${(2 + 3) * 4}` is evaluated
THEN the evaluator SHALL return the integer `20`
