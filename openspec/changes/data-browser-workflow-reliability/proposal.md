## Why

After comprehensive end-to-end testing (114/114 demo operations pass), several gaps remain in the Data Browser, developer workflow, and reliability areas that prevent a complete developer lifecycle experience.

## What Changes

### Data Browser Improvements
- Verify and fix Firestore data browsing (seed data may not appear)
- Verify Spanner CRUD buttons work end-to-end with metadata persistence
- Add per-service health indicator to Data Browser tabs
- Fix Pub/Sub message browsing UI polish

### Developer Workflow
- Add per-service reset (currently only "Reset All")
- Add state export as seed YAML
- Add individual emulator health checks to the health endpoint
- Add request body capture for Logs page inspection

### Reliability
- Add per-emulator health checks (not just gateway health)
- Improve startup ordering with per-service readiness
- Add graceful degradation — unhealthy services shown with clear status

## Capabilities

### New Capabilities
- `per-service-management`: Per-service reset, health checks, and status in Data Browser

### Modified Capabilities
_None_

## Impact
- Java server: health check improvements, per-service reset, request body capture
- Console frontend: Data Browser fixes, per-service health, reset buttons
- Console backend: new proxy routes
