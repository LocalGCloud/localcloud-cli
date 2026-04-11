## ADDED Requirements

### Requirement: Service sub-items in sidebar
When the Data Browser page is active, the sidebar SHALL display all 14 services as indented sub-items below the "Data Browser" nav item. Each sub-item SHALL show the service's GCP icon, name, and health status dot.

#### Scenario: Data Browser expanded in sidebar
- **WHEN** the user navigates to the Data Browser page
- **THEN** the sidebar expands a sub-item list below "Data Browser" showing all 14 services with GCP icons and health dots

#### Scenario: Sub-items collapsed on other pages
- **WHEN** the user navigates to Dashboard, Services, Logs, Usage, or Settings
- **THEN** the Data Browser sub-items are collapsed and not visible in the sidebar

### Requirement: Service selection via sidebar
Clicking a service sub-item in the sidebar SHALL select that service and display its data in the full-width content area. The selected service SHALL be highlighted with an active state.

#### Scenario: Select service from sidebar
- **WHEN** the user clicks "Firestore" in the sidebar sub-items
- **THEN** the URL updates to `#/data/firestore`, the Firestore sub-item is highlighted, and Firestore data loads in the content area

#### Scenario: Active service persists across navigation
- **WHEN** the user selects "Spanner" in Data Browser, navigates to Dashboard, then returns to Data Browser
- **THEN** the Spanner sub-item is still selected and Spanner data is displayed

### Requirement: Full-width content area
The Data Browser content area SHALL use the full width available (no in-page explorer panel). The content SHALL display the service header (icon, name, Refresh/Reset buttons) followed by the service-specific view.

#### Scenario: Content uses full width
- **WHEN** the user is viewing Data Browser with any service selected
- **THEN** the content area spans from the sidebar edge to the right edge of the viewport with no intermediate panel

### Requirement: Responsive sidebar collapse
At the 900px breakpoint, service sub-items SHALL be hidden. The Data Browser icon SHALL remain visible in the collapsed sidebar. The last-selected service SHALL be preserved.

#### Scenario: Narrow viewport hides sub-items
- **WHEN** the viewport width is 900px or less
- **THEN** the sidebar shows only icons (52px), Data Browser sub-items are hidden, and the content area shows the last-selected service's data
