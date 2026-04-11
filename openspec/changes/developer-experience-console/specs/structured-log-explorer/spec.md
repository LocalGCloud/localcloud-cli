## ADDED Requirements

### Requirement: Structured log viewer with severity filtering
The Logs page SHALL include a dedicated "Application Logs" section (alongside the existing "Request Logs") that shows Cloud Logging entries with severity color-coding (DEBUG=gray, INFO=blue, WARNING=yellow, ERROR=red, CRITICAL=red+bold), expandable JSON payloads, log name grouping, and severity-based filtering.

#### Scenario: Filter logs by severity
- **WHEN** developer selects "ERROR" severity filter
- **THEN** only ERROR, CRITICAL, ALERT, and EMERGENCY log entries are shown

#### Scenario: Expand structured payload
- **WHEN** developer clicks a log entry with a JSON `text_payload`
- **THEN** the JSON is expanded in a formatted, syntax-highlighted view with collapsible sections

### Requirement: Full-text search across log entries
The log viewer SHALL support full-text search across log names, payloads, and severity fields. Search results SHALL highlight matching text.

#### Scenario: Search for an error message
- **WHEN** developer types "connection refused" in the search box
- **THEN** all log entries containing "connection refused" in their payload are shown with the matching text highlighted

### Requirement: Time-range selection for logs
The log viewer SHALL support filtering by time range — predefined ranges (last 5 minutes, last 1 hour, last 24 hours) and custom range picker. The viewer SHALL show a mini severity distribution chart above the log entries.

#### Scenario: View logs from the last 5 minutes
- **WHEN** developer selects "Last 5 minutes" time range
- **THEN** only log entries from the last 5 minutes are shown, and the severity chart shows the distribution for that period

### Requirement: Log-to-request correlation
Each log entry SHALL display its associated trace/request ID (if available). Clicking the trace ID navigates to the request detail or trace view. This connects "what my code logged" to "what API call was happening."

#### Scenario: Navigate from log to request
- **WHEN** developer sees an ERROR log entry and clicks its trace ID
- **THEN** the console navigates to the request detail drawer showing the API call that generated the log
