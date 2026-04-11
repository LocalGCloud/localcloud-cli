## ADDED Requirements

### Requirement: Request detail drawer with full payload inspection
The console SHALL display a detail drawer when a developer clicks any request in the Logs page. The drawer SHALL show the full HTTP method, URL, request headers, request body, response status, response headers, response body, and timing breakdown (queue time, processing time, total). Request and response bodies SHALL be syntax-highlighted when they contain JSON.

#### Scenario: Inspect a GCS upload request
- **WHEN** developer clicks a POST request to `/upload/storage/v1/b/my-bucket/o` in the Logs page
- **THEN** the detail drawer opens showing the request body (uploaded content), response body (object metadata JSON), all headers, and timing breakdown

#### Scenario: Inspect a failed request
- **WHEN** developer clicks a request that returned 4xx or 5xx
- **THEN** the detail drawer shows the error response body and highlights the status code in red

### Requirement: Full request/response body capture
The RequestLogger SHALL capture complete request and response bodies for all API requests (not sampled). Body capture SHALL be configurable with a max size limit (default 1MB per body). Bodies exceeding the limit SHALL be truncated with a "[truncated]" indicator.

#### Scenario: JSON request body captured
- **WHEN** a client sends a POST request with a JSON body to any emulator endpoint
- **THEN** the full JSON body is stored in the request log and visible in the detail drawer

#### Scenario: Large body truncated
- **WHEN** a client uploads a 5MB file to GCS
- **THEN** the request body in the log shows "[truncated at 1MB]" with the first 1MB of content

### Requirement: cURL export from request detail
The detail drawer SHALL include a "Copy as cURL" button that generates a valid cURL command reproducing the exact request (method, URL, headers, body).

#### Scenario: Copy cURL for replay
- **WHEN** developer clicks "Copy as cURL" on a Spanner executeSql request
- **THEN** the clipboard contains a cURL command with the correct URL, Content-Type header, and JSON body that can be pasted into a terminal

### Requirement: Request replay
The detail drawer SHALL include a "Replay" button that re-sends the exact same request to the emulator and shows the new response alongside the original.

#### Scenario: Replay a failed request after fixing data
- **WHEN** developer fixes seed data and clicks "Replay" on a previously failed request
- **THEN** the request is re-sent and the new response (hopefully 200) is shown next to the original (500) response
