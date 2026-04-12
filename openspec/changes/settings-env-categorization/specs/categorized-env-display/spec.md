## ADDED Requirements

### Requirement: Environment variables grouped into three categories
The Settings page SHALL display environment variables in three distinct sections: **Common**, **SDK Environment Variables**, and **gcloud CLI Overrides**. Each section SHALL be visually separated as a card with a section header.

#### Scenario: Common section shows project variables
- **WHEN** the Settings page loads
- **THEN** the Common section SHALL display `GOOGLE_CLOUD_PROJECT` and `GCLOUD_PROJECT` with their current values

#### Scenario: SDK section shows per-service emulator host variables
- **WHEN** the Settings page loads
- **THEN** the SDK section SHALL display one sub-card per enabled service, each showing its `*_EMULATOR_HOST` (or `REDIS_HOST`) variable and value

#### Scenario: gcloud CLI section shows CLOUDSDK variables
- **WHEN** the Settings page loads
- **THEN** the gcloud CLI section SHALL display `CLOUDSDK_CORE_PROJECT`, `CLOUDSDK_AUTH_ACCESS_TOKEN`, and all `CLOUDSDK_API_ENDPOINT_OVERRIDES_*` variables for enabled services

#### Scenario: Disabled services are excluded
- **WHEN** a service is disabled (not in `LOCALCLOUD_SERVICES`)
- **THEN** its env vars SHALL NOT appear in any category

### Requirement: Per-variable inline copy button
Each environment variable row SHALL include a copy icon button. Clicking it SHALL copy the variable in shell export format (`export KEY="VALUE"`) to the clipboard.

#### Scenario: Copy single variable
- **WHEN** the user clicks the copy icon next to `PUBSUB_EMULATOR_HOST`
- **THEN** the clipboard SHALL contain `export PUBSUB_EMULATOR_HOST="localhost:8085"`
- **AND** a brief "Copied!" indicator SHALL appear for 2 seconds

#### Scenario: Copy icon visual feedback
- **WHEN** the user hovers over a copy icon
- **THEN** the icon SHALL change to a highlight color (using `var(--primary)`)

### Requirement: Per-category Copy All button
Each category section (Common, SDK, gcloud CLI) SHALL include a "Copy All" button that copies all variables in that category in shell export format, one per line.

#### Scenario: Copy All SDK variables
- **WHEN** the user clicks "Copy All" in the SDK section
- **THEN** the clipboard SHALL contain all SDK emulator host variables as `export` lines, one per line

### Requirement: Per-service sample code snippets
Each service sub-card in the SDK section SHALL include an expandable "Sample Code" area with tabbed code snippets.

#### Scenario: Expand sample code
- **WHEN** the user clicks "Sample Code" on the Cloud Storage service card
- **THEN** a tabbed code block SHALL expand showing Python, Node.js, Go, Java, and gcloud CLI tabs
- **AND** the Python tab SHALL be selected by default

#### Scenario: Sample code is runnable
- **WHEN** the user copies a sample code snippet and runs it with the env vars set
- **THEN** the snippet SHALL execute a basic operation against the emulator (e.g., create a bucket, list topics, create a secret)

#### Scenario: gcloud CLI tab shows CLI commands
- **WHEN** the user selects the "gcloud CLI" tab for Pub/Sub
- **THEN** the snippet SHALL show gcloud commands like `gcloud pubsub topics list`

#### Scenario: Services without gcloud support
- **WHEN** a service has no `gcloudApiName` defined (e.g., Memorystore)
- **THEN** the gcloud CLI tab SHALL NOT appear for that service

### Requirement: Env var row displays key and value separately
Each env var row SHALL display the variable name in monospace text and the value in monospace bold, as a horizontal key-value layout with the copy icon at the end.

#### Scenario: Variable row layout
- **WHEN** the env var `STORAGE_EMULATOR_HOST` with value `http://localhost:4443` is displayed
- **THEN** the row SHALL show the key left-aligned, the value right-aligned or after the key, and a copy icon at the far right

### Requirement: Auto-configure command prominent at top
The auto-configure one-liner (`eval "$(curl -s ...)"`) SHALL appear at the top of the Environment Variables area, before the categorized sections, as a highlighted quick-setup command.

#### Scenario: Auto-configure shown first
- **WHEN** the Settings page loads
- **THEN** the first element in the Environment Variables area SHALL be the auto-configure command with a copy button
- **AND** a label like "Quick Setup — run this to set all variables at once"
