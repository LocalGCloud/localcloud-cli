## ADDED Requirements

### Requirement: Connector Call Syntax Pattern Matching

The connector shim SHALL recognize and intercept function calls that match the pattern `googleapis.SERVICE.VERSION.RESOURCE.METHOD`. All five dot-separated segments MUST be present for the call to be treated as a connector call. Calls that do not match this pattern MUST be passed through to the normal function dispatch mechanism.

#### Scenario: Recognize valid connector call pattern

WHEN a workflow step calls `googleapis.storage.v1.buckets.list` with the required arguments
THEN the connector shim SHALL intercept the call and route it to the GCS emulator rather than invoking a stdlib function

#### Scenario: Pass through non-connector call

WHEN a workflow step calls `text.to_upper("hello")` which does not match the `googleapis.SERVICE.VERSION.RESOURCE.METHOD` pattern
THEN the connector shim SHALL NOT intercept the call and SHALL allow normal stdlib dispatch to proceed

#### Scenario: Reject incomplete connector pattern

WHEN a workflow step calls `googleapis.storage.v1.buckets` which has only four segments
THEN the connector shim SHALL NOT treat the call as a connector call and SHALL pass it to normal dispatch

---

### Requirement: Route Connector Calls to LocalCloud Emulators

The connector shim SHALL route intercepted connector calls to the appropriate LocalCloud emulator running on localhost according to the following mapping: `storage.v1` to GCS on port 4443, `bigquery.v2` to BigQuery on port 9050, `pubsub.v1` to Pub/Sub on port 8085, `firestore.v1` to Firestore on port 8086, `secretmanager.v1` to Secret Manager on port 8080, `cloudtasks.v2` to Cloud Tasks on port 8080, and `spanner.v1` to Spanner on port 9010. The shim MUST construct a valid HTTP or gRPC request targeting the appropriate localhost address.

#### Scenario: Route storage.v1 call to GCS emulator

WHEN a workflow step calls `googleapis.storage.v1.buckets.list` with project argument
THEN the connector shim SHALL send the corresponding HTTP request to `http://localhost:4443` and return the response

#### Scenario: Route secretmanager.v1 call to Secret Manager emulator

WHEN a workflow step calls `googleapis.secretmanager.v1.projects.secrets.versions.access` with the appropriate arguments
THEN the connector shim SHALL send the corresponding request to `http://localhost:8080` and return the response

#### Scenario: Route pubsub.v1 call to Pub/Sub emulator

WHEN a workflow step calls `googleapis.pubsub.v1.projects.topics.publish` with a topic and messages argument
THEN the connector shim SHALL send the corresponding request to `http://localhost:8085` and return the response

---

### Requirement: Supported Connector Registry

The connector shim SHALL maintain a registry of supported connectors: `storage.v1`, `bigquery.v2`, `pubsub.v1`, `firestore.v1`, `secretmanager.v1`, `cloudtasks.v2`, and `spanner.v1`. Any `googleapis.*` call where the `SERVICE.VERSION` segment matches a registered entry SHALL be routed to the corresponding emulator. All other `googleapis.*` calls SHALL be treated as unknown connectors.

#### Scenario: Route registered connector to emulator

WHEN a workflow step calls `googleapis.bigquery.v2.jobs.query`
THEN the connector shim SHALL identify `bigquery.v2` in the supported registry and route the call to `http://localhost:9050`

#### Scenario: Identify unsupported connector

WHEN a workflow step calls `googleapis.translate.v3.projects.translateText`
THEN the connector shim SHALL NOT find `translate.v3` in the supported registry and SHALL treat the call as an unknown connector

---

### Requirement: Auth Bypass for Local Emulator Calls

The connector shim SHALL bypass OIDC and OAuth2 authentication for all calls routed to LocalCloud emulators. Auth-related arguments such as `auth: {type: "OIDC"}` or `auth: {type: "OAuth2"}` MUST be silently ignored and MUST NOT cause the shim to attempt token acquisition. No Authorization header SHALL be added to emulator-bound requests.

#### Scenario: Bypass OIDC auth for GCS emulator call

WHEN a workflow step calls `googleapis.storage.v1.buckets.list` with `auth: {type: "OIDC", audience: "https://example.com"}`
THEN the connector shim SHALL route the call to `http://localhost:4443` without adding any Authorization header

#### Scenario: Bypass OAuth2 auth for Firestore emulator call

WHEN a workflow step calls `googleapis.firestore.v1.projects.databases.documents.list` with `auth: {type: "OAuth2"}`
THEN the connector shim SHALL route the call to `http://localhost:8086` without performing any token exchange

---

### Requirement: LRO Handling for Long-Running Operations

Connector calls that return a long-running operation (LRO) on the real Google Cloud API SHALL return the completed result immediately in the emulator. The connector shim MUST NOT perform polling against an operations endpoint. The shim SHALL wait for the emulator response synchronously and return the final result as though the operation completed instantly.

#### Scenario: Return immediate result for LRO connector call

WHEN a workflow step calls a connector method that would return an LRO on real Google Cloud (such as a Spanner database create operation)
THEN the connector shim SHALL return the completed operation result directly without polling and without returning an in-progress operation object

#### Scenario: No operation polling step generated for LRO

WHEN a connector call produces an LRO response from the emulator
THEN the connector shim SHALL unwrap the operation result and return the inner response directly so the workflow does not need a separate wait or poll step

---

### Requirement: Unknown Connector Fallback Behavior

When the connector shim intercepts a `googleapis.*` call where the `SERVICE.VERSION` segment is not in the supported registry, the shim MUST log a warning identifying the unknown connector. The shim SHALL then attempt to construct a direct HTTP call to the URL derived from the connector service name and version using standard Google API URL patterns. The result of that HTTP call SHALL be returned as the connector result.

#### Scenario: Log warning for unknown connector

WHEN a workflow step calls `googleapis.translate.v3.projects.translateText`
THEN the connector shim SHALL emit a warning log entry stating that the connector `translate.v3` is not in the supported registry

#### Scenario: Attempt direct HTTP call for unknown connector

WHEN a workflow step calls `googleapis.vision.v1.images.annotate` and `vision.v1` is not in the supported registry
THEN the connector shim SHALL construct an HTTP request to the URL derived from `https://vision.googleapis.com/v1/images:annotate` and return the HTTP response as the connector result

---

### Requirement: Connector Arguments Mapping to HTTP Requests

The connector shim SHALL translate connector call arguments to HTTP requests by mapping named parameters to URL path templates, query parameters, and the request body. The URL template for each supported connector method SHALL be derived from the Google Discovery Document conventions. Path parameters MUST be substituted into the URL template. Non-path parameters MUST be included as query parameters for GET and DELETE methods, or as the JSON request body for POST, PUT, and PATCH methods.

#### Scenario: Substitute path parameter into URL template

WHEN a workflow step calls `googleapis.secretmanager.v1.projects.secrets.versions.access` with `name` set to `"projects/my-project/secrets/my-secret/versions/latest"`
THEN the connector shim SHALL construct the URL `http://localhost:8080/v1/projects/my-project/secrets/my-secret/versions/latest:access`

#### Scenario: Include non-path args as query parameters for GET

WHEN a workflow step calls `googleapis.storage.v1.objects.list` with `bucket` as a path parameter and `prefix` as a filter argument
THEN the connector shim SHALL append `?prefix=<value>` to the request URL

#### Scenario: Serialize non-path args as JSON body for POST

WHEN a workflow step calls `googleapis.pubsub.v1.projects.topics.publish` with a `messages` argument
THEN the connector shim SHALL serialize `messages` as the JSON body of an HTTP POST request

---

### Requirement: HTTP Error Mapping to Workflows Error Types

When a connector call receives an HTTP error response (4xx or 5xx), the connector shim SHALL translate the error into a Workflows `HttpError` type containing the `code` (integer HTTP status code) and `message` (string description) fields. The workflow execution MUST be able to catch this error using standard Workflows exception handling syntax. Non-HTTP transport errors SHALL be surfaced as a `ConnectionError` type.

#### Scenario: Map 404 HTTP response to HttpError

WHEN a connector call to the Secret Manager emulator returns an HTTP 404 response
THEN the connector shim SHALL raise a Workflows `HttpError` with `code` set to `404` and `message` populated from the response body

#### Scenario: Map 500 HTTP response to HttpError

WHEN a connector call to the BigQuery emulator returns an HTTP 500 response
THEN the connector shim SHALL raise a Workflows `HttpError` with `code` set to `500`

#### Scenario: Map connection failure to ConnectionError

WHEN a connector call targets a LocalCloud emulator port that is not accepting connections
THEN the connector shim SHALL raise a Workflows `ConnectionError` describing the failed connection attempt
