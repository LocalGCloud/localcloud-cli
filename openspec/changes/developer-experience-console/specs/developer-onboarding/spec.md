## ADDED Requirements

### Requirement: Per-service SDK code snippets
The Data Browser SHALL show a "Connect" or "Code" tab for each service displaying copy-pasteable code snippets in Python, Java, Go, and Node.js. Snippets SHALL show how to configure the client to point at the local emulator and perform a basic operation (e.g., list buckets, publish a message, run a query).

#### Scenario: Copy Python snippet for GCS
- **WHEN** developer views GCS in the Data Browser and clicks the "Code" tab, selects Python
- **THEN** a code snippet is shown: `from google.cloud import storage; client = storage.Client(); buckets = list(client.list_buckets())` with a note about setting `STORAGE_EMULATOR_HOST`

#### Scenario: Switch language
- **WHEN** developer clicks "Java" tab
- **THEN** the snippet updates to show Java `StorageOptions.newBuilder().setHost(...)` pattern

### Requirement: Getting-started wizard on first visit
The console SHALL show a guided wizard on the first visit (dismissible, remembered via localStorage). Steps: (1) verify services are healthy, (2) copy environment variables, (3) try a sample API call from the code snippet, (4) see the request appear in logs. The wizard SHALL highlight the relevant UI elements at each step.

#### Scenario: First-time developer walkthrough
- **WHEN** developer opens the console for the first time
- **THEN** a step-by-step wizard guides them through setup: check health → copy env vars → run sample code → verify in logs

#### Scenario: Dismiss and re-access
- **WHEN** developer dismisses the wizard
- **THEN** the wizard does not appear again, but is accessible via a "Getting Started" link in Settings

### Requirement: Notification center for system events
The console SHALL display a notification bell icon showing unread event count. Notifications include: service health changes, seed data loaded, data reset, fault injection active, emulator errors. Clicking the bell opens a notification panel with recent events.

#### Scenario: Service becomes unhealthy
- **WHEN** the Spanner emulator process crashes
- **THEN** a notification badge appears: "Spanner became unhealthy" with timestamp, and the Dashboard card turns red
