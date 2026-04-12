## ADDED Requirements

### Requirement: CopyableCodeBlock component for all code blocks
Every code block in the Settings page SHALL include a copy-to-clipboard button. A unified `CopyableCodeBlock` component SHALL render code content with an optional label and a copy icon that shows "Copied!" feedback for 2 seconds.

#### Scenario: Copy code from Help tab
- **WHEN** the user clicks the copy button on a code block in the Help tab
- **THEN** the code content SHALL be copied to the clipboard
- **AND** the icon SHALL change to a checkmark for 2 seconds

#### Scenario: All code blocks have copy buttons
- **WHEN** the user views any tab in Settings
- **THEN** every code block (shell commands, SDK snippets, CLI commands, guide examples) SHALL have a copy button

### Requirement: Collapsible service cards in CLI and SDK tabs
Each service section in the CLI and SDK tabs SHALL be collapsed by default, showing only the service icon and name. Users SHALL click to expand and see the code snippet.

#### Scenario: Default collapsed state
- **WHEN** the user opens the SDK tab
- **THEN** all 9 service sections SHALL be collapsed (showing only name + icon)

#### Scenario: Expand a service
- **WHEN** the user clicks on the "Cloud Storage" header
- **THEN** the Cloud Storage code snippet SHALL expand with a smooth animation

#### Scenario: Expand All / Collapse All
- **WHEN** the user clicks "Expand All" at the top of the SDK tab
- **THEN** all service sections SHALL expand simultaneously
- **AND** the button label SHALL change to "Collapse All"

### Requirement: No duplicated content across tabs
The Settings page SHALL NOT display the same information in multiple tabs. Each piece of content SHALL have a single authoritative location.

#### Scenario: eval command appears once
- **WHEN** the user searches for the auto-configure eval command
- **THEN** it SHALL appear in the Environment tab's Quick Setup card only
- **AND** the Help tab SHALL reference "See Environment tab" instead of repeating it

#### Scenario: Env var lists not duplicated
- **WHEN** the Help tab discusses SDK setup
- **THEN** it SHALL link to the Environment tab instead of listing all env vars again

### Requirement: Docker-first Quick Start
The Quick Start guide in the Help tab SHALL show `docker run` as the primary startup command, not `docker compose up -d`. The guide SHALL assume the user has only the Docker image, not the source repo.

#### Scenario: Quick Start primary path
- **WHEN** the user reads the Quick Start guide
- **THEN** the first command SHALL be `docker run -d --name localcloud -p 8080:8080 ... localcloud/localcloud:latest`
- **AND** the next step SHALL be the eval command to set env vars
- **AND** a simple SDK test (3-line Python snippet) SHALL follow

#### Scenario: Docker Compose as alternative
- **WHEN** the user scrolls past the primary Docker run path
- **THEN** a "Using Docker Compose" section SHALL show the compose alternative for users with the repo

### Requirement: Local vs Cloud switching guide in Environment tab
The Environment tab SHALL include a concise "Switch to Google Cloud" callout below the Quick Setup card, showing how to unset env vars to revert to real GCP.

#### Scenario: Switching guide visible
- **WHEN** the user views the Environment tab
- **THEN** a compact callout SHALL show: "To switch back to Google Cloud, unset all variables:" with a one-line unset command and copy button

### Requirement: Static data extracted to separate file
The `SAMPLE_CODE`, `CLI_COMMANDS`, `SERVICE_META`, and `SDK_ORDER` constants SHALL be in a separate `settings-data.js` file, imported by Settings.jsx.

#### Scenario: Settings.jsx imports data
- **WHEN** a developer reads Settings.jsx
- **THEN** the component logic SHALL be visible without scrolling past ~280 lines of static data
