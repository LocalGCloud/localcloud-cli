## ADDED Requirements

### Requirement: Routing badge on service cards
Each service card on the Dashboard SHALL display a routing badge showing "Local", "Cloud", or "Unknown" next to the health status dot.

#### Scenario: Service routed locally
- **WHEN** the routing API reports a service as "local"
- **THEN** the service card displays a green "Local" badge

#### Scenario: Service routed to Google Cloud
- **WHEN** the routing API reports a service as "cloud" or the user has overridden it to "cloud"
- **THEN** the service card displays a blue "Cloud" badge

#### Scenario: Routing unknown
- **WHEN** the routing API reports a service as "unknown"
- **THEN** the service card displays a gray "Unknown" badge

### Requirement: Routing badge on Services table
The Services page table SHALL include a "Routing" column showing the routing badge for each service.

#### Scenario: Routing column visible
- **WHEN** the user navigates to APIs & Services page
- **THEN** a "Routing" column displays the Local/Cloud/Unknown badge per service row

### Requirement: Routing indicator in sidebar
The sidebar sub-items under Data Browser SHALL show a small routing indicator icon (local or cloud) alongside the existing health status dot.

#### Scenario: Sidebar shows routing
- **WHEN** the Data Browser sidebar is expanded
- **THEN** each service sub-item displays both a health dot and a routing indicator

### Requirement: User can override routing status
Users SHALL be able to click the routing badge to toggle between "Local" and "Cloud" for any service. The override SHALL persist in localStorage.

#### Scenario: User overrides to Cloud
- **WHEN** the user clicks the "Local" badge on Cloud Storage
- **THEN** the badge changes to "Cloud" and the override is saved to localStorage key `localcloud-routing-overrides`

#### Scenario: User clears override
- **WHEN** the user clicks the overridden badge again
- **THEN** the badge reverts to the auto-detected value from the routing API

#### Scenario: Override persists across refreshes
- **WHEN** the user has set a routing override and refreshes the page
- **THEN** the override is restored from localStorage and displayed correctly

### Requirement: Routing badge tooltip
Each routing badge SHALL display a tooltip on hover explaining what the indicator means and how to override it.

#### Scenario: Tooltip on Local badge
- **WHEN** the user hovers over a "Local" badge
- **THEN** a tooltip shows "Traffic routes to LocalCloud emulator. Click to override."
