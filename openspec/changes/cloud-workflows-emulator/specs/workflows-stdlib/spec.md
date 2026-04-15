## ADDED Requirements

### Requirement: HTTP Namespace Functions

The stdlib SHALL provide the `http` namespace with the functions `http.get`, `http.post`, `http.put`, `http.patch`, and `http.delete`. Each function MUST accept a required `url` argument and the optional arguments `headers` (map), `body` (any), `query` (map), `auth` (map with `type` key set to `OIDC` or `OAuth2`), and `timeout` (number of seconds). The default timeout SHALL be 300 seconds. Each function MUST return a map containing the keys `body`, `code`, and `headers`. Retry behavior on 5xx responses MUST be configurable via the `retry` argument.

#### Scenario: Perform HTTP GET and receive response map

WHEN the expression `${http.get("http://localhost:8080/health")}` is evaluated
THEN the stdlib SHALL execute an HTTP GET request to `http://localhost:8080/health` and return a map with the keys `body`, `code`, and `headers` populated from the response

#### Scenario: Apply default timeout of 300 seconds

WHEN `http.get` is called without a `timeout` argument
THEN the HTTP request SHALL use a timeout of 300 seconds

#### Scenario: Include custom headers in HTTP POST request

WHEN `http.post` is called with `url` set to `"http://example.com/api"` and `headers` set to `{"Content-Type": "application/json"}`
THEN the outbound HTTP request SHALL include the `Content-Type: application/json` header

#### Scenario: Retry on 5xx response when retry is configured

WHEN `http.get` is called with a `retry` argument specifying retry conditions for 5xx responses and the server returns a 503
THEN the stdlib SHALL retry the request according to the configured retry policy

---

### Requirement: sys Namespace Functions

The stdlib SHALL provide the `sys` namespace with the following functions: `sys.get_env(name)`, `sys.log(text, severity)`, `sys.now()`, and `sys.sleep(seconds)`. `sys.get_env` MUST return the value of the named environment variable or `null` if it is not set. `sys.log` MUST emit a log entry with the provided text and severity; severity MUST be one of `INFO`, `WARNING`, or `ERROR`. `sys.now()` MUST return the current UTC timestamp as a string. `sys.sleep` MUST pause execution for the specified number of seconds and MUST NOT accept a value greater than 60 in the emulator.

#### Scenario: Retrieve existing environment variable

WHEN `sys.get_env("HOME")` is called and the `HOME` environment variable is set on the host
THEN the function SHALL return the value of the `HOME` environment variable as a string

#### Scenario: Return null for missing environment variable

WHEN `sys.get_env("NONEXISTENT_VAR_XYZ")` is called and the variable is not set
THEN the function SHALL return `null`

#### Scenario: Log message at INFO severity

WHEN `sys.log("workflow started", "INFO")` is called
THEN the stdlib SHALL emit a log entry with the text `workflow started` and severity `INFO`

#### Scenario: Error when sleep exceeds emulator limit

WHEN `sys.sleep(61)` is called
THEN the stdlib SHALL return an error indicating that the maximum sleep duration of 60 seconds has been exceeded

#### Scenario: Return current UTC timestamp from sys.now

WHEN `sys.now()` is called
THEN the function SHALL return a non-empty string representing the current UTC datetime in ISO 8601 format

---

### Requirement: json Namespace Functions

The stdlib SHALL provide `json.decode(string)` and `json.encode(object)`. `json.decode` MUST parse a valid JSON string and return the corresponding Workflows value (map, list, string, number, boolean, or null). `json.decode` MUST return an error when the input is not valid JSON. `json.encode` MUST serialize a Workflows value to a valid JSON string.

#### Scenario: Decode valid JSON string to map

WHEN `json.decode("{\"key\": \"value\"}")` is called
THEN the function SHALL return the map `{"key": "value"}`

#### Scenario: Error on invalid JSON input to json.decode

WHEN `json.decode("not valid json")` is called
THEN the function SHALL return a parse error indicating the input is not valid JSON

#### Scenario: Encode map to JSON string

WHEN `json.encode({"a": 1, "b": true})` is called
THEN the function SHALL return a valid JSON string representing the map

---

### Requirement: base64 Namespace Functions

The stdlib SHALL provide `base64.encode(value)` and `base64.decode(string)`. `base64.encode` MUST accept a string or byte sequence and return the standard Base64-encoded string. `base64.decode` MUST accept a Base64-encoded string and return the decoded string. `base64.decode` MUST return an error when the input is not valid Base64.

#### Scenario: Encode string to Base64

WHEN `base64.encode("hello")` is called
THEN the function SHALL return the string `aGVsbG8=`

#### Scenario: Decode valid Base64 string

WHEN `base64.decode("aGVsbG8=")` is called
THEN the function SHALL return the string `hello`

#### Scenario: Error on invalid Base64 input

WHEN `base64.decode("!!!invalid!!!")` is called
THEN the function SHALL return an error indicating the input is not valid Base64

---

### Requirement: math Namespace Functions

The stdlib SHALL provide `math.abs`, `math.ceil`, `math.floor`, `math.max`, `math.min`, and `math.round`. Each function MUST accept numeric arguments and return a numeric result. `math.max` and `math.min` MUST accept at least two arguments. Passing a non-numeric argument to any math function MUST return a type error.

#### Scenario: Compute absolute value of negative number

WHEN `math.abs(-7)` is called
THEN the function SHALL return `7`

#### Scenario: Compute ceiling of float

WHEN `math.ceil(2.3)` is called
THEN the function SHALL return `3`

#### Scenario: Return maximum of two numbers

WHEN `math.max(4, 9)` is called
THEN the function SHALL return `9`

#### Scenario: Error on non-numeric argument to math function

WHEN `math.abs("text")` is called
THEN the function SHALL return a type error

---

### Requirement: text Namespace Functions

The stdlib SHALL provide the following functions in the `text` namespace: `text.find_all(string, regex)`, `text.match_regex(string, regex)`, `text.replace_all(string, regex, replacement)`, `text.split(string, delimiter)`, `text.substring(string, start, end)`, `text.to_lower(string)`, `text.to_upper(string)`, `text.url_encode(string)`, and `text.url_decode(string)`. Each function MUST accept string arguments and return a string or list as appropriate. Invalid regex patterns MUST produce a regex error.

#### Scenario: Convert string to upper case

WHEN `text.to_upper("hello world")` is called
THEN the function SHALL return the string `HELLO WORLD`

#### Scenario: Split string on delimiter

WHEN `text.split("a,b,c", ",")` is called
THEN the function SHALL return the list `["a", "b", "c"]`

#### Scenario: Extract substring

WHEN `text.substring("abcdef", 1, 4)` is called
THEN the function SHALL return the string `bcd`

#### Scenario: URL-encode a string

WHEN `text.url_encode("hello world")` is called
THEN the function SHALL return the string `hello+world` or `hello%20world` per standard URL encoding rules

#### Scenario: Replace all regex matches

WHEN `text.replace_all("foo bar foo", "foo", "baz")` is called
THEN the function SHALL return the string `baz bar baz`

#### Scenario: Error on invalid regex pattern

WHEN `text.match_regex("hello", "[invalid")` is called
THEN the function SHALL return a regex error indicating the pattern is invalid

---

### Requirement: list Namespace Functions

The stdlib SHALL provide `list.concat(list1, list2)`, `list.prepend(list, element)`, and `list.sort(list)`. `list.concat` MUST return a new list containing all elements of `list1` followed by all elements of `list2`. `list.prepend` MUST return a new list with `element` inserted at position 0. `list.sort` MUST return a new list with elements sorted in ascending order; sorting a list with mixed incompatible types MUST return an error.

#### Scenario: Concatenate two lists

WHEN `list.concat([1, 2], [3, 4])` is called
THEN the function SHALL return the list `[1, 2, 3, 4]`

#### Scenario: Prepend element to list

WHEN `list.prepend([2, 3], 1)` is called
THEN the function SHALL return the list `[1, 2, 3]`

#### Scenario: Sort list of integers in ascending order

WHEN `list.sort([3, 1, 2])` is called
THEN the function SHALL return the list `[1, 2, 3]`

#### Scenario: Error when sorting list with mixed types

WHEN `list.sort([1, "a", 2])` is called
THEN the function SHALL return an error indicating that mixed-type sorting is not supported

---

### Requirement: map Namespace Functions

The stdlib SHALL provide `map.get(map, key, default)`, `map.keys(map)`, `map.values(map)`, and `map.merge(map1, map2)`. `map.get` MUST return the value for `key` if present, otherwise return `default`. `map.keys` MUST return a list of all keys in the map. `map.values` MUST return a list of all values in the map. `map.merge` MUST return a new map combining both maps; when keys conflict, values from `map2` MUST take precedence.

#### Scenario: Get existing key from map

WHEN `map.get({"a": 1}, "a", 0)` is called
THEN the function SHALL return `1`

#### Scenario: Return default for missing key

WHEN `map.get({"a": 1}, "b", 99)` is called
THEN the function SHALL return `99`

#### Scenario: Return keys of map as list

WHEN `map.keys({"x": 1, "y": 2})` is called
THEN the function SHALL return a list containing `"x"` and `"y"` (order unspecified)

#### Scenario: Merge maps with conflict resolved by second map

WHEN `map.merge({"a": 1, "b": 2}, {"b": 99, "c": 3})` is called
THEN the function SHALL return the map `{"a": 1, "b": 99, "c": 3}`

---

### Requirement: Type Cast Functions

The stdlib SHALL provide the top-level type cast functions `int(value)`, `double(value)`, and `string(value)`. `int` MUST convert a float by truncating toward zero and MUST parse a numeric string as an integer. `double` MUST convert an integer to a float and MUST parse a numeric string as a float. `string` MUST convert any scalar value to its string representation. Conversion failures MUST produce a type error.

#### Scenario: Cast float to integer by truncation

WHEN `int(3.9)` is called
THEN the function SHALL return the integer `3`

#### Scenario: Cast integer to double

WHEN `double(5)` is called
THEN the function SHALL return the float `5.0`

#### Scenario: Cast integer to string

WHEN `string(42)` is called
THEN the function SHALL return the string `"42"`

#### Scenario: Error on invalid string-to-int cast

WHEN `int("not_a_number")` is called
THEN the function SHALL return a type error indicating the value cannot be converted to an integer
