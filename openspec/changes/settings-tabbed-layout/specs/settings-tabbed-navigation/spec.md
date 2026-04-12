## ADDED Requirements

### Requirement: Settings page has a top-level tab bar
The Settings page SHALL display a tab bar below the page header with 4 tabs: Environment, Cloud & Routing, Preferences, Help & About. Only the selected tab's content SHALL be visible.

#### Scenario: Default tab on first visit
- **WHEN** the user navigates to Settings for the first time
- **THEN** the "Environment" tab SHALL be selected and its content visible

#### Scenario: Tab switching
- **WHEN** the user clicks "Cloud & Routing" tab
- **THEN** the GCP Credentials and Service Routing sections SHALL be visible
- **AND** the Environment sections SHALL be hidden

### Requirement: Tab selection persists across page refreshes
The selected tab SHALL be stored in localStorage and restored when the Settings page is reopened.

#### Scenario: Tab persistence
- **WHEN** the user selects "Preferences" tab and refreshes the page
- **THEN** the "Preferences" tab SHALL be selected after refresh

### Requirement: User Guide displayed inline in Help tab
The User Guide content (Quick Start, SDK Setup, gcloud CLI, Revert, Seed Data, Admin API) SHALL be displayed directly in the Help & About tab as nested sub-tabs, not in a modal overlay.

#### Scenario: Guide content inline
- **WHEN** the user clicks the "Help & About" tab
- **THEN** the guide sub-tabs SHALL be visible inline with the About section below

#### Scenario: No modal for guide
- **WHEN** the user is on the Help & About tab
- **THEN** there SHALL be no "Open Guide" button or modal overlay

### Requirement: Segmented toggle for routing mode
The Service Routing table SHALL use a segmented toggle (Local | Remote) instead of a dropdown `<select>` for each service's routing mode.

#### Scenario: Toggle visual state
- **WHEN** a service is in Local mode
- **THEN** the "Local" segment SHALL be active (green) and "Remote" segment inactive

#### Scenario: Remote disabled without credentials
- **WHEN** GCP credentials are not configured
- **THEN** the "Remote" segment SHALL be visually disabled with a tooltip explaining that credentials are required

#### Scenario: Toggle interaction
- **WHEN** the user clicks the "Remote" segment for a service
- **THEN** the system SHALL call `PUT /_localcloud/routing/{service}` with `mode: "remote"`
