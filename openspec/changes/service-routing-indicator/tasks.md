## 1. Backend: Routing Detection API

- [ ] 1.1 Add `GET /_localcloud/routing` endpoint to AdminService that returns per-service routing status (`emulatorRunning`, `healthy`, `routing`, `port`, `envVar`)
- [ ] 1.2 Implement routing detection logic: check supervisord process status for external services, check in-memory flag for facade services
- [ ] 1.3 Derive routing value: "local" if running+healthy, "cloud" if not running, "unknown" if running but unhealthy
- [ ] 1.4 Write unit tests for routing endpoint (all-local, mixed, all-cloud scenarios)

## 2. Backend: Service Enable/Disable API

- [ ] 2.1 Add `POST /_localcloud/services/{id}/enable` endpoint that starts the emulator process (supervisord for external, flag toggle for facade)
- [ ] 2.2 Add `POST /_localcloud/services/{id}/disable` endpoint that stops the emulator process (supervisord for external, flag toggle + 503 for facade)
- [ ] 2.3 Add supervisord XML-RPC client utility to start/stop named processes
- [ ] 2.4 Add in-memory `enabledServices` map to gate facade service request routing with 503 response when disabled
- [ ] 2.5 Return `already_enabled` / `already_disabled` when service is already in the requested state
- [ ] 2.6 Write unit tests for enable/disable endpoints (external services, facade services, idempotent calls)

## 3. Console: API Client

- [ ] 3.1 Add `api.routing()` method to fetch `/_localcloud/routing`
- [ ] 3.2 Add `api.enableService(id)` and `api.disableService(id)` methods
- [ ] 3.3 Add routing data to the auto-refresh effect in app.jsx alongside health data

## 4. Console: Routing Badge Component

- [ ] 4.1 Create `RoutingBadge` component with three states: Local (green), Cloud (blue), Unknown (gray)
- [ ] 4.2 Add click handler to toggle user override (Local → Cloud → auto-detect cycle)
- [ ] 4.3 Add tooltip on hover explaining the routing indicator and override capability
- [ ] 4.4 Store/load routing overrides in localStorage key `localcloud-routing-overrides`
- [ ] 4.5 Add CSS classes `.badge-local`, `.badge-cloud`, `.badge-unknown` to components.css

## 5. Console: Dashboard Integration

- [ ] 5.1 Add routing badge to Dashboard service cards next to the health status dot
- [ ] 5.2 Merge routing data with health data in the services() computed signal
- [ ] 5.3 Show "Disabled" badge (dimmed card) for disabled services instead of health/routing badges

## 6. Console: Services Page Integration

- [ ] 6.1 Add "Routing" column to the Services table showing the routing badge
- [ ] 6.2 Add enable/disable toggle switch to each service row in the Services table
- [ ] 6.3 Wire toggle to call `api.enableService()` / `api.disableService()` and refresh status
- [ ] 6.4 Show disabled services with dimmed row styling

## 7. Console: Sidebar Integration

- [ ] 7.1 Add small routing indicator icon to sidebar sub-items alongside health dot
- [ ] 7.2 Show dimmed text and gray dot for disabled services in sidebar

## 8. Build and Verify

- [ ] 8.1 Build console (`npm run build`) and verify no build errors
- [ ] 8.2 Run Java server tests (`./gradlew test`) and verify new tests pass
- [ ] 8.3 Visual verification: screenshot Dashboard, Services page, and sidebar with routing badges
